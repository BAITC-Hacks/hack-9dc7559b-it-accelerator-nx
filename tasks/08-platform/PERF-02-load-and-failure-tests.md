---
id: PERF-02
owner: D3
status: todo
wave: 5
size: L
depends_on: ["NFR-01", "PERF-01", "OPS-02", "QA-01", "QA-02"]
---

# PERF-02 — Нагрузка 1k→3k→5k, soak и отказоустойчивость

**Исполнитель:** D3. **Этап:** G5. **Объём:** L. Все задачи обязательны для полного scope; отметка todo означает, что реализация не выполнена.
**Зависимости для старта:** [NFR-01](../00-foundation/NFR-01-workload-and-release-gates.md), [PERF-01](../08-platform/PERF-01-limits-events-and-metrics.md), [OPS-02](../08-platform/OPS-02-complete-product-compose.md), [QA-01](../09-quality/QA-01-end-to-end-acceptance.md), [QA-02](../09-quality/QA-02-rag-and-recognition-evaluation.md)
**Покрытие:** AC-16; NFR latency/RPS.

## Goal

Доказать оговорённую capacity собственного контура и отдельно проверить зависимости.

## Context

Успешный docker compose smoke не подтверждает5kRPS, а быстрые429 могут исказить throughput.

Основные решения: [единое ТЗ](../../docs/ekt-assistant-spec.md). Общий workflow и финальные gates: [tasks/README.md](../README.md).

## План реализации

1. Реализовать open-arrival profile NFR-01 в k6/Gatling/другом проверенном SSE driver; если REST runner не читает SSE, отдельный driver и общий учёт.
2. Real auth/DB/search/Cart domains работают; mocks provider держат realistic TTFT/duration/chunks и failures, embeddings/OCR rates учитываются отдельно.
3. Прогреть corpus/cache по профилю, распределить load generators и проверить, что их sockets/CPU не bottleneck.
4. Ступени1k3k5k по15мин после прогрева,60мин soak; metrics offered/admitted/completed/rejected/dropped, p95/p99, TTFT/full completion, active connections, queue/memory/pools.
5. Failure suite: worker/Redis restart, slow readers, retries, stock/cart timeout; history сохраняет SLO и нет unbounded state.
6. Real OpenAI/partner этап только в выделенных quotas/budget с gradual ramp; провайдерные ограничения отражать отдельно от mock результатов.
7. Зафиксировать hardware/replicas/version/traffic mix/cachehit/tokenrounds, raw report и sizing/лимитации; D1/D2 исправляют свои bottlenecks.

## Область изменений

tests/load/; docker-compose.load.yml; scripts/test-compose.sh load mode; docs/reports/performance/

Пути с многоточием обозначают предлагаемые package/file locations, а не уже существующие файлы. Публичный контракт, env и номера миграций согласовать по ownership в README.

## Constraints

- Нагрузка не запускается на shared demo или production.
- SSE chunks не новые HTTP requests; auth/status/reconnect учитываются в mix.
- Не объявлять high-load gate выполненным только по ping либо99%rejections.
- Соблюдать [AGENTS.md](../../AGENTS.md), [backend/AGENTS.md](../../backend/AGENTS.md) и [frontend/AGENTS.md](../../frontend/AGENTS.md) для изменяемой области.

## Done when

- [ ] AC-16 SLO из NFR-01 проверены с success/rejection gates, stable queue и bounded memory.
- [ ] 5k измерены на полном выбранном mix, не только read cache endpoint.
- [ ] Report различает local mock capacity и подтверждённую real dependency capacity.
- [ ] При неполном external evidence статус AC-16 pending/failed, не success.
- [ ] Проверки выполнены на своей ветке; PR содержит результат проверок и известные ограничения.

## Проверка и передача

При backend-изменениях: `cd backend && ./gradlew build`; при frontend: `cd frontend && npm run build && npm run lint`. API изменился → поднять backend и выполнить `cd frontend && npm run gen`. Помимо сборки воспроизвести перечисленные выше позитивные и негативные сценарии; mocks портов не считаются финальной продуктовой приёмкой.

Проверку нового clean/upgrade DB проводить в отдельном Compose project/volume. Общую demo-БД не сбрасывать. Результаты бизнес-сценариев после интеграции входят в QA-01/QA-02; нагрузка — PERF-02.

**Передать:** REL-01: воспроизводимый performance report и размер deploy; D1/D2: bottleneck evidence.

**Evidence после выполнения:** заполнить commit/PR, команды, ссылки на отчёты и фактический результат; до выполнения статус остаётся todo.
