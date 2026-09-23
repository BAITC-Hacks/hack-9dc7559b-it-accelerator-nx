---
id: CHAT-02
owner: D1
status: in_progress
wave: 2
size: L
depends_on: ["FOUND-01", "CHAT-01"]
---

# CHAT-02 — OpenAI agent с ограниченным tool loop

**Исполнитель:** D1. **Этап:** G2. **Объём:** L. Реализация D1 в работе; непроверенные критерии не считаются выполненными.
**Зависимости для старта:** [FOUND-01](../00-foundation/FOUND-01-contracts-and-ports.md), [CHAT-01](../02-chat/CHAT-01-history-and-runs.md)
**Покрытие:** FR-1…7; AC-1…10.

## Goal

Оркестрировать консультацию через разрешённые инструменты и проверенные данные.

## Context

ТЗ требует search_catalog/check_stock/find_analogs/propose_cart_addition; один LLM call не покрывает весь flow.

Основные решения: [единое ТЗ](../../docs/ekt-assistant-spec.md). Общий workflow и финальные gates: [tasks/README.md](../README.md).

## План реализации

1. Провести совместимость Spring AI 1.0.0 и выбранного API/модели для streaming tool calls, usage, cancellation/vision; зафиксировать один real path и fake adapters.
2. Реализовать tools через ports FOUND-01: search, stock, analogs, FAQ, reviewed attachment, propose only; фakes позволяют начать без D2.
3. Валидировать tool schema/arguments и trusted scope сервером, сохранять toolCallId/results; unknown tool/invalid arguments не исполнять.
4. Ограничить rounds, parallel calls, per-tool timeout, total tokens и run deadline; cancellation доходит до активного request.
5. Строить factual product/proposal cards из backend DTO; коммерческие числовые утверждения выводить из проверенных snapshots, не свободных model values.
6. Versioned prompt и context budget сохраняют память диалога; sources из retrieval allowlist; no-answer/source-unavailable честно отражаются.
7. Сохранить model/request IDs и usage без raw personal content; настоящие D2 adapters подключить на integration gate без изменения port schema.

## Область изменений

backend/…/ai/agent/; ai/gateway/; config/; src/test/…/ai/

Пути с многоточием обозначают предлагаемые package/file locations, а не уже существующие файлы. Публичный контракт, env и номера миграций согласовать по ownership в README.

## Constraints

- Cart mutation tool отсутствует; модельный confirmed flag ничего не авторизует.
- Строгая JSON schema не заменяет domain validation.
- Не дублировать retry SDK и приложения; после выданного stream unknown replay не запускать.
- Соблюдать [AGENTS.md](../../AGENTS.md), [backend/AGENTS.md](../../backend/AGENTS.md) и [frontend/AGENTS.md](../../frontend/AGENTS.md) для изменяемой области.

## Done when

- [ ] Fake provider пытается вызвать cart_add/SQL/неизвестный tool — side effect отсутствует.
- [ ] Exact SKU, stock0, FAQ и attachment review дают соответствующие typed events; неподтверждённые цены не возникают.
- [ ] Loop/time/token exhaustion завершается контролируемо, очередь/потоки освобождаются.
- [ ] Live OpenAI smoke подтверждает настоящий tool continuation и stream; fake mode отдельно маркирован.
- [ ] Проверки выполнены на своей ветке; PR содержит результат проверок и известные ограничения.

## Проверка и передача

При backend-изменениях: `cd backend && ./gradlew build`; при frontend: `cd frontend && npm run build && npm run lint`. API изменился → поднять backend и выполнить `cd frontend && npm run gen`. Помимо сборки воспроизвести перечисленные выше позитивные и негативные сценарии; mocks портов не считаются финальной продуктовой приёмкой.

Проверку нового clean/upgrade DB проводить в отдельном Compose project/volume. Общую demo-БД не сбрасывать. Результаты бизнес-сценариев после интеграции входят в QA-01/QA-02; нагрузка — PERF-02.

**Передать:** D2: зарегистрированные tools и ошибки ports; D3: typed result events; QA: scripted provider cases и live smoke evidence.

**Evidence после выполнения:** заполнить commit/PR, команды, ссылки на отчёты и фактический результат; до подтверждения всех критериев статус остаётся in_progress.

**Текущий handoff:** [реализация D1 и открытые проверки](../../docs/api/d1-handoff.md). Статус `in_progress`; результаты final integration run пока pending.
