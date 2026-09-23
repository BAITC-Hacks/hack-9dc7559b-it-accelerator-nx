---
id: UI-01
owner: D3
status: todo
wave: 1
size: M
depends_on: ["FOUND-01"]
---

# UI-01 — Generated клиент, auth и HTTP/SSE fixtures

**Исполнитель:** D3. **Этап:** G1. **Объём:** M. Все задачи обязательны для полного scope; отметка todo означает, что реализация не выполнена.
**Зависимости для старта:** [FOUND-01](../00-foundation/FOUND-01-contracts-and-ports.md)
**Покрытие:** Все FR; AC-13/17.

## Goal

Начать frontend параллельно backend без ручных HTTP-типов.

## Context

Generated axios client имеет fetch SSE; текущий axios interceptor не добавляет token в этот stream.

Основные решения: [единое ТЗ](../../docs/ekt-assistant-spec.md). Общий workflow и финальные gates: [tasks/README.md](../README.md).

## План реализации

1. Сгенерировать SDK из springdoc snapshot FOUND-01 через npm run gen -- --input ../docs/api/openapi.json; canonical localhost config сохранить.
2. Настроить shared auth callback для generated REST и SSE, bounded reconnect policy и session cache cleanup.
3. Создать typed fixtures satisfies GeneratedDto и mock HTTP/SSE handlers для product/proposal/attachment/error/terminal states.
4. Fixtures используют тот же SDK и wire format; держать их в dev/test entry, отключить в итоговом Compose UI.
5. Добавить schema snapshot drift check и regenerate instructions; после API PR не редактировать generated файлы для исправления ошибок.
6. Передать D1/D2 найденные несовпадения контрактов и получить новый snapshot до расширения UI.

## Область изменений

frontend/openapi-ts.config.ts; src/client/** generated only; src/lib/api.ts; src/mocks/; package*.json; frontend tests/

Пути с многоточием обозначают предлагаемые package/file locations, а не уже существующие файлы. Публичный контракт, env и номера миграций согласовать по ownership в README.

## Constraints

- HTTP-типы вручную запрещены, включая SSE event union.
- UI fixtures не заменяют реальный backend в финальном E2E.
- 401 останавливает retries; 429/503 следуют согласованной политике, не infinite loop helper.
- Соблюдать [AGENTS.md](../../AGENTS.md), [backend/AGENTS.md](../../backend/AGENTS.md) и [frontend/AGENTS.md](../../frontend/AGENTS.md) для изменяемой области.

## Done when

- [ ] Клиент генерируется из snapshot и живого backend; frontend build/lint проходят.
- [ ] Bearer/session identity реально присутствует в REST и fetch SSE request.
- [ ] Все product/cart/attachment union variants отображаются через typed fixtures.
- [ ] Test mock mode не включён в live frontend build.
- [ ] Проверки выполнены на своей ветке; PR содержит результат проверок и известные ограничения.

## Проверка и передача

При backend-изменениях: `cd backend && ./gradlew build`; при frontend: `cd frontend && npm run build && npm run lint`. API изменился → поднять backend и выполнить `cd frontend && npm run gen`. Помимо сборки воспроизвести перечисленные выше позитивные и негативные сценарии; mocks портов не считаются финальной продуктовой приёмкой.

Проверку нового clean/upgrade DB проводить в отдельном Compose project/volume. Общую demo-БД не сбрасывать. Результаты бизнес-сценариев после интеграции входят в QA-01/QA-02; нагрузка — PERF-02.

**Передать:** D3 UI-02…05: общий transport и fixtures; D1/D2: contract feedback без блокировки бизнес-разработки.

**Evidence после выполнения:** заполнить commit/PR, команды, ссылки на отчёты и фактический результат; до выполнения статус остаётся todo.
