---
id: QA-01
owner: D3
status: todo
wave: 5
size: L
depends_on: ["OPS-02", "UI-02", "UI-03", "UI-04", "CHAT-04", "CART-03", "CAT-04", "ATT-04"]
acceptance_depends_on: ["CHAT-03", "PERF-01"]
---

# QA-01 — Приёмка AC-1…14 и AC-17 через настоящий виджет

**Исполнитель:** D3. **Этап:** G5. **Объём:** L. Все задачи обязательны для полного scope; отметка todo означает, что реализация не выполнена.
**Зависимости для старта:** [OPS-02](../08-platform/OPS-02-complete-product-compose.md), [UI-02](../07-widget/UI-02-chat-and-streaming.md), [UI-03](../07-widget/UI-03-products-proposals-and-cart.md), [UI-04](../07-widget/UI-04-attachments-review.md), [CHAT-04](../02-chat/CHAT-04-dialogue-context.md), [CART-03](../05-cart/CART-03-cart-adapters-and-reconciliation.md), [CAT-04](../03-catalog/CAT-04-analogs-and-fulfillment.md), [ATT-04](../06-attachments/ATT-04-matching-and-review.md)
**Покрытие:** AC-1…14/17; FR-1…7.

**Дополнительно для итоговой приёмки:** [CHAT-03](../02-chat/CHAT-03-sse-recovery.md) и [PERF-01](../08-platform/PERF-01-limits-events-and-metrics.md) должны быть интегрированы в проверяемый checkout. Подготовка и частичные прогоны начинаются раньше; финальные SSE/reconnect/restart/429 проверки выполняются на реализации recovery и общих лимитов. См. [схему зависимостей](../DEPENDENCIES.md).

## Goal

Проверить, что три ветки дают целый продукт, а не отдельные работающие компоненты.

## Context

Ранние UI mocks и domain tests не доказывают путь host iframe→backend→data/tools→Cart.

Основные решения: [единое ТЗ](../../docs/ekt-assistant-spec.md). Общий workflow и финальные gates: [tasks/README.md](../README.md).

## План реализации

1. Подготовить stable browser selectors и scenario fixtures ещё на UI-01; финальный запуск через OPS-02 real backend.
2. Покрыть AC1 exactSKU/specs/cert,AC2stock0analog,AC3FAQ source,AC8missingSKU,AC10followups.
3. Проверить AC4 no-confirm unchanged;AC5exact add;AC6changed stock/price conflict;AC7cartURL;AC9 choice12+8/full20.
4. Confirm отдельно проверить кнопкой и новым «да» с точным proposalId/version; «да» без единственного pending proposal, «не добавляй», цитата и смена quantity перед «да» дают0mutations. Подтверждение обрабатывается вне LLM loop; повтор успешно завершённой operation после expiry возвращает её прежний результат.
5. Каждый xls/xlsx/doc/docx/PDF/JPEG, включаяscan/photo без текста, провести upload→review→proposal; uncertain rows не добавляются автоматически. Дубли строк одного SKU суммируются перед stock check.
6. Два visitors, forged parent message, source/proposal ACL, document injection, stale confirmation, doubleclick, restart, timeout-after-success, SSEduplicate/EOF/401/429/cancel.
7. Проверить desktop и390×844 iframe host page, keyboard/scroll/focus, reload session; fresh isolated DB и V2 upgrade.
8. Reports содержат screenshots/network assertions и ID критерия; неисправности возвращаются D1/D2 владельцу и перепроверяются targeted.
## Область изменений

frontend browser/e2e tests; tests/integration/; data/expected/; reports/ (generated ignored); CI config

Пути с многоточием обозначают предлагаемые package/file locations, а не уже существующие файлы. Публичный контракт, env и номера миграций согласовать по ownership в README.

## Constraints

- Не подменять реальный backend mock handlers в финальном E2E.
- Cart assert читает persisted CartSnapshot, а не только текст «добавлено».
- Offline provider не свидетельствует о качестве live AI; это отдельный запуск/QA-02.
- Соблюдать [AGENTS.md](../../AGENTS.md), [backend/AGENTS.md](../../backend/AGENTS.md) и [frontend/AGENTS.md](../../frontend/AGENTS.md) для изменяемой области.

## Done when

- [ ] Все AC1…14 и17 имеют pass/fail report из Compose и повторяемые fixtures.
- [ ] Проверены все обязательные расширения, а не только один XLSX и текстовыйPDF.
- [ ] Без согласия0mutations, повторconfirm1mutation, unknown outcome сверяется.
- [ ] Тестовый runner завершает CI nonzero при нарушении критерия; reports сохранены.
- [ ] Итоговый прогон включает CHAT-03 и PERF-01; результаты частичного прогона до их интеграции не закрывают AC-14.
- [ ] Проверки выполнены на своей ветке; PR содержит результат проверок и известные ограничения.

## Проверка и передача

При backend-изменениях: `cd backend && ./gradlew build`; при frontend: `cd frontend && npm run build && npm run lint`. API изменился → поднять backend и выполнить `cd frontend && npm run gen`. Помимо сборки воспроизвести перечисленные выше позитивные и негативные сценарии; mocks портов не считаются финальной продуктовой приёмкой.

Проверку нового clean/upgrade DB проводить в отдельном Compose project/volume. Общую demo-БД не сбрасывать. Результаты бизнес-сценариев после интеграции входят в QA-01/QA-02; нагрузка — PERF-02.

**Передать:** D1/D2: reproduction для их багов; REL-01: функциональный acceptance report и browser evidence.

**Evidence после выполнения:** заполнить commit/PR, команды, ссылки на отчёты и фактический результат; до выполнения статус остаётся todo.
