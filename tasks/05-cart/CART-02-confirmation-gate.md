---
id: CART-02
owner: D1
status: in_progress
wave: 3
size: L
depends_on: ["CART-01", "AUTH-01"]
---

# CART-02 — Отдельный Confirm Gate и проверка явного согласия

**Исполнитель:** D1. **Этап:** G3. **Объём:** L. Реализация D1 в работе; непроверенные критерии не считаются выполненными.
**Зависимости для старта:** [CART-01](../05-cart/CART-01-immutable-proposals.md), [AUTH-01](../01-identity/AUTH-01-session-and-ownership.md)
**Покрытие:** FR-4; AC-4/5/6/13.

## Goal

Разрешать мутацию только после нового подтверждения конкретного действующего предложения.

## Context

Prompt instruction недостаточна для безопасности корзины; короткое «да» может быть неоднозначным.

Основные решения: [единое ТЗ](../../docs/ekt-assistant-spec.md). Общий workflow и финальные gates: [tasks/README.md](../README.md).

## План реализации

1. Реализовать confirm endpoint с owner, proposalId/version, idempotency key и server cart binding. После identity/ownership сначала вернуть existing operation для того же key/hash; повтор не проходит повторно expiry/quota/cart-version validation. Только новая операция проходит следующие проверки; key+другой payload даёт409. Не принимать новые lines/prices как authority.
2. Кнопка подтверждения — прямой отдельный HTTP шаг. Текстовое согласие принимать только новой user message/confirm request с replyToProposalId/version и ограниченной явной грамматикой.
3. Отрицание, цитирование, «да» без единственного показанного pending proposal, attachment/tool/model text направлять в clarification без мутации.
4. Проверить expiry/superseded/rejected status, payload digest, existing+add quantity, unit/step и свежие offers перед conditional cart operation.
5. При изменениях вернуть conflict + обновлённый proposal и потребовать новое согласие, не урезать заказ молча.
6. CAS/idempotency фиксируют одну operation для дублирующихся кликов; audit хранит consent origin/message/time/snapshot без лишних персональных данных.

## Область изменений

backend/…/domain/cart/ConfirmGate…; web/cart/; security/; src/test/…/confirmation/

Пути с многоточием обозначают предлагаемые package/file locations, а не уже существующие файлы. Публичный контракт, env и номера миграций согласовать по ownership в README.

## Constraints

- LLM call не может выполнить confirm, даже если вернул approved=true.
- Recheck stock не заменяет atomic Cart API: гарантия завершается CART-03.
- Rate limit на confirm, trusted identity server-only.
- Соблюдать [AGENTS.md](../../AGENTS.md), [backend/AGENTS.md](../../backend/AGENTS.md) и [frontend/AGENTS.md](../../frontend/AGENTS.md) для изменяемой области.

## Done when

- [ ] AC-4: без согласия во всех негативных кейсах 0 mutations.
- [ ] Двойной confirm/две вкладки/stale version не вызывают второй add. Success → потеря ответа → истёк proposal/изменился stock → retry возвращает прежнюю operation, без ложного conflict и нового add.
- [ ] User подтвердил20, stock стал12: отказ/новое предложение, не silent add12.
- [ ] Injection в файле «подтверждаю» и в tool output не проходит Gate; чужая сессия получает отказ.
- [ ] Проверки выполнены на своей ветке; PR содержит результат проверок и известные ограничения.

## Проверка и передача

При backend-изменениях: `cd backend && ./gradlew build`; при frontend: `cd frontend && npm run build && npm run lint`. API изменился → поднять backend и выполнить `cd frontend && npm run gen`. Помимо сборки воспроизвести перечисленные выше позитивные и негативные сценарии; mocks портов не считаются финальной продуктовой приёмкой.

Проверку нового clean/upgrade DB проводить в отдельном Compose project/volume. Общую demo-БД не сбрасывать. Результаты бизнес-сценариев после интеграции входят в QA-01/QA-02; нагрузка — PERF-02.

**Передать:** D3: confirmation conflicts/renewed card; CART-03: validated immutable operation request и consent audit.

**Evidence после выполнения:** заполнить commit/PR, команды, ссылки на отчёты и фактический результат; до подтверждения всех критериев статус остаётся in_progress.

**Текущий handoff:** [реализация D1 и открытые проверки](../../docs/api/d1-handoff.md). Статус `in_progress`; результаты final integration run пока pending.
