---
id: UI-03
owner: D3
status: todo
wave: 3
size: L
depends_on: ["UI-02"]
---

# UI-03 — Товары, аналоги, подтверждение и актуальная корзина

**Исполнитель:** D3. **Этап:** G3. **Объём:** L. Все задачи обязательны для полного scope; отметка todo означает, что реализация не выполнена.
**Зависимости для старта:** [UI-02](../07-widget/UI-02-chat-and-streaming.md)
**Покрытие:** FR-1…5; AC-1…10.

## Goal

Дать выбрать товар/комплектацию и отдельно подтвердить точный состав.

## Context

Цена/stock/cert/source — серверные DTO, cart result нельзя подменить optimistic state.

Основные решения: [единое ТЗ](../../docs/ekt-assistant-spec.md). Общий workflow и финальные gates: [tasks/README.md](../README.md).

## План реализации

1. Показать ProductDetails, units/current offers/freshness/unknown status, safe certificates и FAQ sources.
2. Для analogs показать verified совпадения/различия, вариант12+8 и full20; selection только выбирает option, не изменяет корзину.
3. Proposal card рендерит immutable lines/price/quantity/total/version/expiry и отдельные confirm/reject controls.
4. Confirm отправляет proposalId/version и стабильный idempotency key; natural yes привязывается к показанному replyToProposalId, неоднозначность требует вопроса.
5. Stale/price_changed/stock_changed рисует новую карточку и новое согласие; unknown outcome показывает status lookup вместо ложного success.
6. GET cart page и link/badge отражают persisted snapshot после success; duplicate click/retry не делает optimistic add.
7. Safe Markdown без raw HTML/опасных URLs; source URL валидируется backend и текущей session.

## Область изменений

frontend/src/components/catalog/; components/cart/; pages/CartPage.tsx; components/sources/; browser tests/

Пути с многоточием обозначают предлагаемые package/file locations, а не уже существующие файлы. Публичный контракт, env и номера миграций согласовать по ownership в README.

## Constraints

- В UI нельзя подставить придуманные model price/stock.
- Изменение quantity/composition не наследует старое согласие.
- Никаких реальных partner writes в fixture/live-AI sample mode.
- Соблюдать [AGENTS.md](../../AGENTS.md), [backend/AGENTS.md](../../backend/AGENTS.md) и [frontend/AGENTS.md](../../frontend/AGENTS.md) для изменяемой области.

## Done when

- [ ] AC-1…9 карточки и choice/confirm работают с expected values.
- [ ] Без confirm/cancel/close cart unchanged; double click даёт один mutation.
- [ ] После stock12→7 при confirmation нет silent substitution, показано новое предложение.
- [ ] Ссылка открывает актуальную cart page той же session, чужие данные недоступны.
- [ ] Проверки выполнены на своей ветке; PR содержит результат проверок и известные ограничения.

## Проверка и передача

При backend-изменениях: `cd backend && ./gradlew build`; при frontend: `cd frontend && npm run build && npm run lint`. API изменился → поднять backend и выполнить `cd frontend && npm run gen`. Помимо сборки воспроизвести перечисленные выше позитивные и негативные сценарии; mocks портов не считаются финальной продуктовой приёмкой.

Проверку нового clean/upgrade DB проводить в отдельном Compose project/volume. Общую demo-БД не сбрасывать. Результаты бизнес-сценариев после интеграции входят в QA-01/QA-02; нагрузка — PERF-02.

**Передать:** QA-01: полный purchase-selection UI path; D1/D2: snapshots и конкретные несовпадения factual values.

**Evidence после выполнения:** заполнить commit/PR, команды, ссылки на отчёты и фактический результат; до выполнения статус остаётся todo.
