---
id: UI-02
owner: D3
status: todo
wave: 2
size: L
depends_on: ["UI-01"]
---

# UI-02 — Чат, история, streaming и восстановление

**Исполнитель:** D3. **Этап:** G2. **Объём:** L. Все задачи обязательны для полного scope; отметка todo означает, что реализация не выполнена.
**Зависимости для старта:** [UI-01](../07-widget/UI-01-client-and-fixtures.md)
**Покрытие:** FR-7; AC-10/14.

## Goal

Собрать используемый чат со стабильным поведением при reload, cancel и сети.

## Context

Сейчас HomePage показывает ping; новая UI работа сначала идёт на fixtures UI-01.

Основные решения: [единое ТЗ](../../docs/ekt-assistant-spec.md). Общий workflow и финальные gates: [tasks/README.md](../README.md).

## План реализации

1. Создать conversation/history/composer с loading/error/empty, cursor paging и session-scoped query keys.
2. Отправка сохраняет client id/idempotency key на network retry; новый run создаёт mutation, не React effect/subscription.
3. SSE reducer обрабатывает typed events/seq/epoch, dedup, terminal и snapshot; batch text updates без refetch на каждый token.
4. Normal EOF без terminal→status check/bounded reconnect, replay expiry→snapshot,401→session handling.
5. Stop вызывает server cancel и закрывает transport; переход между чатами не смешивает активный текст.
6. Показать known filters/selection контекста и поддержать короткие follow-up сообщения; auto-scroll не мешает чтению истории.
7. Интегрировать реальные CHAT endpoints на G2, сохранив те же generated types.

## Область изменений

frontend/src/pages/; components/chat/; lib/query.ts; hooks/; frontend browser tests/

Пути с многоточием обозначают предлагаемые package/file locations, а не уже существующие файлы. Публичный контракт, env и номера миграций согласовать по ownership в README.

## Constraints

- Не считать закрытие stream успешным ответом.
- При logout/switch principal очищать private cache/buffers.
- Успех корзины не выводить из свободного текста LLM.
- Соблюдать [AGENTS.md](../../AGENTS.md), [backend/AGENTS.md](../../backend/AGENTS.md) и [frontend/AGENTS.md](../../frontend/AGENTS.md) для изменяемой области.

## Done when

- [ ] История/активный run восстанавливаются после reload, новая отправка не дублируется.
- [ ] SSE disconnect/duplicate/expiry/401/429/cancel fixtures работают без бесконечного spinner.
- [ ] AC-10 continuation остаётся в том же conversation, known parameters видны.
- [ ] Browser test через реальный backend подтверждает stream flow на integration gate.
- [ ] Проверки выполнены на своей ветке; PR содержит результат проверок и известные ограничения.

## Проверка и передача

При backend-изменениях: `cd backend && ./gradlew build`; при frontend: `cd frontend && npm run build && npm run lint`. API изменился → поднять backend и выполнить `cd frontend && npm run gen`. Помимо сборки воспроизвести перечисленные выше позитивные и негативные сценарии; mocks портов не считаются финальной продуктовой приёмкой.

Проверку нового clean/upgrade DB проводить в отдельном Compose project/volume. Общую demo-БД не сбрасывать. Результаты бизнес-сценариев после интеграции входят в QA-01/QA-02; нагрузка — PERF-02.

**Передать:** D1: reproducible UX/network failures; UI-03/04: chat event slots и composer attachment integration.

**Evidence после выполнения:** заполнить commit/PR, команды, ссылки на отчёты и фактический результат; до выполнения статус остаётся todo.
