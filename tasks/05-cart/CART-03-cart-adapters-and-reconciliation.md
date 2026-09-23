---
id: CART-03
owner: D1
status: todo
wave: 4
size: L
depends_on: ["CART-02"]
---

# CART-03 — Stateful Cart API, атомарность и неизвестный исход

**Исполнитель:** D1. **Этап:** G4. **Объём:** L. Все задачи обязательны для полного scope; отметка todo означает, что реализация не выполнена.
**Зависимости для старта:** [CART-02](../05-cart/CART-02-confirmation-gate.md)
**Покрытие:** FR-4/5; AC-5/6/7/14.

## Goal

Получить настоящее состояние тестовой корзины и надёжный adapter контракт для партнёра.

## Context

Partner API может отсутствовать; approved mock должен воспроизводить state, concurrency и failures, а не всегда success.

Основные решения: [единое ТЗ](../../docs/ekt-assistant-spec.md). Общий workflow и финальные gates: [tasks/README.md](../README.md).

## План реализации

1. Реализовать mock CartPort в PostgreSQL: cart owner/version, lines, atomic conditional add multi-line, stock/price validation из sample authoritative store.
2. Перед atomic validation агрегировать existing cart + все proposal lines по нормализованному SKU/unit/warehouse stock bucket. Проверять sum totals, conversions/step и warehouse policy; cart add не считается stock reservation/checkout.
3. Идемпотентная operation storage и getCart/lookupOperation позволяют после lost response получить прежний outcome.
4. Сделать HTTP partner adapter границу и contract tests; реальные endpoint/auth/version/quantity/atomicity guarantees проверить до включения live partner mode.
5. Timeout-after-success сохранять outcome_unknown и reconcile по operation ID; не повторять blindly. Пока outcome unknown, UI не заявляет success/failure без сверки.
6. CartSnapshot/URL выдавать после подтверждённого commit; sample URL ведёт к странице D3, реальные URLs allowlisted.
7. Добавить price/stock race, concurrent cart edits, restart до/после внешнего ответа и multi-line failure fixtures.

## Область изменений

backend/…/integration/cart/; domain/cart/Operation…; web/cart/; db/migration/; src/test/…/cart/

Пути с многоточием обозначают предлагаемые package/file locations, а не уже существующие файлы. Публичный контракт, env и номера миграций согласовать по ownership в README.

## Constraints

- Локальная БД транзакция не гарантирует exactly-once внешнему Cart API.
- Нет atomic/lookup поддержки партнёра — соответствующий production gate остаётся непроверенным.
- Не изменять реальную корзину при live-AI smoke, по умолчанию sample cart.
- Соблюдать [AGENTS.md](../../AGENTS.md), [backend/AGENTS.md](../../backend/AGENTS.md) и [frontend/AGENTS.md](../../frontend/AGENTS.md) для изменяемой области.

## Done when

- [ ] AC-5/6: точные quantities и no overflow при гонках; multi-line mock all-or-nothing. Две строки одинакового SKU по8 при остатке12 и эквивалентные единицы не обходят stock limit.
- [ ] Повтор confirmation/перезапуск не добавляет второй раз; timeout-after-success сверяется.
- [ ] AC-7 URL показывает серверную корзину той же session, другая session не имеет доступа.
- [ ] Контрактные тесты sample/HTTP adapters одинаковы; integration limitation явно записана.
- [ ] Проверки выполнены на своей ветке; PR содержит результат проверок и известные ограничения.

## Проверка и передача

При backend-изменениях: `cd backend && ./gradlew build`; при frontend: `cd frontend && npm run build && npm run lint`. API изменился → поднять backend и выполнить `cd frontend && npm run gen`. Помимо сборки воспроизвести перечисленные выше позитивные и негативные сценарии; mocks портов не считаются финальной продуктовой приёмкой.

Проверку нового clean/upgrade DB проводить в отдельном Compose project/volume. Общую demo-БД не сбрасывать. Результаты бизнес-сценариев после интеграции входят в QA-01/QA-02; нагрузка — PERF-02.

**Передать:** D3: GET cart, operation status, checkout link; QA-01: deterministic race/unknown-outcome controls; PERF-01: protected cart metrics.

**Evidence после выполнения:** заполнить commit/PR, команды, ссылки на отчёты и фактический результат; до выполнения статус остаётся todo.
