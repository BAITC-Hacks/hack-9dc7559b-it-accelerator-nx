---
id: CART-01
owner: D1
status: todo
wave: 3
size: M
depends_on: ["CHAT-02"]
---

# CART-01 — Предложения корзины без изменения корзины

**Исполнитель:** D1. **Этап:** G3. **Объём:** M. Все задачи обязательны для полного scope; отметка todo означает, что реализация не выполнена.
**Зависимости для старта:** [CHAT-02](../02-chat/CHAT-02-agent-tools.md)
**Покрытие:** FR-4/5; AC-4/9.

## Goal

Создавать точные immutable proposals из выбранных catalog/attachment items.

## Context

LLM может только предложить действие; пользователь должен видеть цены, количество и состав.

Основные решения: [единое ТЗ](../../docs/ekt-assistant-spec.md). Общий workflow и финальные gates: [tasks/README.md](../README.md).

## План реализации

1. Реализовать proposal/lines schema, owner/conversation/cart binding, revision/digest, expiry и operation ID.
2. Получать SKU/quantity из разрешённого выбора/review, все цены/units/stock — свежим StockPort lookup.
3. Определить addQuantity как прибавку к cart existing quantities; нормализовать inventory key SKU+unit+warehouse/stock bucket и агрегировать повторяющиеся строки. Сохранить snapshot состава/price/cart version и provenance исходных строк.
4. propose_cart_addition и POST proposals только сохраняют pending proposal; toolCallId dedup предотвращает дубли при replay.
5. Новые quantity/выбор/price context supersede предыдущий proposal; reject/expiry сохраняются без Cart mutation.
6. Публиковать typed card event, не произвольный JSON/URL модели; неготовые attachment rows исключать.

## Область изменений

backend/…/domain/cart/Proposal…; web/cart/; ai/agent/tools/; db/migration/; src/test/…/cart/

Пути с многоточием обозначают предлагаемые package/file locations, а не уже существующие файлы. Публичный контракт, env и номера миграций согласовать по ownership в README.

## Constraints

- Модель не получает CartPort mutation и не передаёт доверенное confirmed=true.
- Изменение состава всегда новая версия, никогда silent update подписанного snapshot.
- Подтверждается весь multi-line набор, а не только первая строка.
- Соблюдать [AGENTS.md](../../AGENTS.md), [backend/AGENTS.md](../../backend/AGENTS.md) и [frontend/AGENTS.md](../../frontend/AGENTS.md) для изменяемой области.

## Done when

- [ ] Предложение, его replay, закрытие чата и reject не вызывают cart mutation.
- [ ] Snapshot содержит точные units/price/quantity/expiry; hash не зависит от свободного текста модели.
- [ ] Чужие resultSet/attachment/proposal IDs отвергаются.
- [ ] Partial12+8 и full20 дают разные proposals и требуют отдельного выбора.
- [ ] Проверки выполнены на своей ветке; PR содержит результат проверок и известные ограничения.

## Проверка и передача

При backend-изменениях: `cd backend && ./gradlew build`; при frontend: `cd frontend && npm run build && npm run lint`. API изменился → поднять backend и выполнить `cd frontend && npm run gen`. Помимо сборки воспроизвести перечисленные выше позитивные и негативные сценарии; mocks портов не считаются финальной продуктовой приёмкой.

Проверку нового clean/upgrade DB проводить в отдельном Compose project/volume. Общую demo-БД не сбрасывать. Результаты бизнес-сценариев после интеграции входят в QA-01/QA-02; нагрузка — PERF-02.

**Передать:** D3: immutable proposal card; CART-02: consent target; D2: требования к reviewed lines/offer versions.

**Evidence после выполнения:** заполнить commit/PR, команды, ссылки на отчёты и фактический результат; до выполнения статус остаётся todo.
