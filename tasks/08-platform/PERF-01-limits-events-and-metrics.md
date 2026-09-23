---
id: PERF-01
owner: D1
status: in_progress
wave: 4
size: L
depends_on: ["NFR-01", "CHAT-03", "CART-03"]
---

# PERF-01 — Общие лимиты, replay нескольких реплик и метрики

**Исполнитель:** D1. **Этап:** G4. **Объём:** L. Реализация D1 в работе; непроверенные критерии не считаются выполненными.
**Зависимости для старта:** [NFR-01](../00-foundation/NFR-01-workload-and-release-gates.md), [CHAT-03](../02-chat/CHAT-03-sse-recovery.md), [CART-03](../05-cart/CART-03-cart-adapters-and-reconciliation.md)
**Покрытие:** AC-14/16; NFR доступность.

## Goal

Сохранить контроль ресурсов и корректность state при нескольких backend replicas.

## Context

Локальные counters/replay buffers не обеспечивают общие quota/routing; новые workers не должны дублировать операции.

Основные решения: [единое ТЗ](../../docs/ekt-assistant-spec.md). Общий workflow и финальные gates: [tasks/README.md](../README.md).

## План реализации

1. Реализовать Redis global/per-principal RPM/token reservation/concurrency budgets и отдельные cart/upload/tool limits с bounded queue age.
2. Перед provider call резервировать budget, сверять usage; duplicate idempotency replay не резервирует повторно.
3. Redis Streams short journal с TTL/size bound, monotonic seq/epoch и authorized replay через любую replica; durable final остаётся в PostgreSQL.
4. Fencing и terminal checks защищают publisher; stale worker/cancel/Redis reconnect не возобновляют запрещённые deltas.
5. Изолировать HTTP/DB/parser/LLM pools; сумма DB pool replicas не превышает PostgreSQL budget; circuit breakers/retry policy не скрывают unavailable source.
6. Метрики route p95/p99, TTFT, completed/rejected/queueAge, activeSSE, tokens/tools/OCR, cart unknown, pool waits, Redis memory; traces без raw prompts и high-cardinality IDs.
7. Graceful drain API/worker при deploy, finite deadline и snapshot fallback при Redis journal loss; fail-closed платных/опасных операций при недоступной обязательной проверке.

## Область изменений

backend/…/config/; domain/chat/events/; ai/agent/limits/; integration/; application.yml; metrics tests/

Пути с многоточием обозначают предлагаемые package/file locations, а не уже существующие файлы. Публичный контракт, env и номера миграций согласовать по ownership в README.

## Constraints

- Redis не единственное хранилище ответа/cart operation.
- Нельзя снимать лимиты ради достижения RPS; history не зависит от живого provider.
- Sticky sessions не заменяют recovery/общую quota.
- Соблюдать [AGENTS.md](../../AGENTS.md), [backend/AGENTS.md](../../backend/AGENTS.md) и [frontend/AGENTS.md](../../frontend/AGENTS.md) для изменяемой области.

## Done when

- [ ] POST наA, SSE наB, reconnect наC сохраняют согласованный ответ без дублей.
- [ ] Общая квота не умножается на число replicas, limiter outage имеет документированное поведение.
- [ ] Redis restart/slow client/worker lease loss не дают unbounded memory или stale side effects.
- [ ] Счётчики offered/admitted/completed и latency различают собственный и provider bottleneck.
- [ ] Проверки выполнены на своей ветке; PR содержит результат проверок и известные ограничения.

## Проверка и передача

При backend-изменениях: `cd backend && ./gradlew build`; при frontend: `cd frontend && npm run build && npm run lint`. API изменился → поднять backend и выполнить `cd frontend && npm run gen`. Помимо сборки воспроизвести перечисленные выше позитивные и негативные сценарии; mocks портов не считаются финальной продуктовой приёмкой.

Проверку нового clean/upgrade DB проводить в отдельном Compose project/volume. Общую demo-БД не сбрасывать. Результаты бизнес-сценариев после интеграции входят в QA-01/QA-02; нагрузка — PERF-02.

**Передать:** D3 PERF-02: instrumentation и scale profile; QA-01: failure/reconnect scenarios; D2: worker quota isolation.

**Evidence после выполнения:** заполнить commit/PR, команды, ссылки на отчёты и фактический результат; до подтверждения всех критериев статус остаётся in_progress.

**Текущий handoff:** [реализация D1 и открытые проверки](../../docs/api/d1-handoff.md). Статус `in_progress`; результаты final integration run пока pending.
