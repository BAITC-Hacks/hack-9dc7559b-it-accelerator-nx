---
id: AUTH-01
owner: D1
status: in_progress
wave: 1
size: M
depends_on: ["FOUND-01"]
---

# AUTH-01 — Visitor/partner identity и изоляция ресурсов

**Исполнитель:** D1. **Этап:** G1. **Объём:** M. Реализация D1 в работе; непроверенные критерии не считаются выполненными.
**Зависимости для старта:** [FOUND-01](../00-foundation/FOUND-01-contracts-and-ports.md)
**Покрытие:** AC-13; FR-4; NFR приватность.

## Goal

Заменить permitAll проверяемой identity и объектной авторизацией.

## Context

Виджет может жить в iframe; клиент сайта и cart context ещё не интегрированы.

Основные решения: [единое ТЗ](../../docs/ekt-assistant-spec.md). Общий workflow и финальные gates: [tasks/README.md](../README.md).

## План реализации

1. Реализовать ограниченную server-issued visitor session для sample режима; token имеет expiry/audience, principal и server cart binding.
2. Вынести проверяемый partner identity exchange за интерфейс; credentials/cartId не брать из недоверенного body или parent message.
3. Авторизовать conversation/run/events/resultSet/attachment/proposal/cart/source; admin catalog/KB writes закрыть отдельной ролью.
4. Согласовать D3 bearer/session transport для REST и fetch SSE, refresh/reload и logout cache cleanup.
5. Настроить allowed origins/methods/headers, embed parent allowlist, token TTL и session rate limits; если выбраны cookies, добавить соответствующую CSRF защиту.
6. Сделать integration cases двух visitors и admin; публичными оставить только нужные auth/docs/health routes.

## Область изменений

backend/src/main/java/com/hackalem/security/; web/auth/; config/; backend/src/test/…/security/

Пути с многоточием обозначают предлагаемые package/file locations, а не уже существующие файлы. Публичный контракт, env и номера миграций согласовать по ownership в README.

## Constraints

- UUID не authorization; tenant/principal вычисляется сервером.
- Не хранить/запрашивать платёжные данные; JWT secret только env.
- Нельзя выпускать новый session/cart по произвольному partner identity без криптографической проверки.
- Соблюдать [AGENTS.md](../../AGENTS.md), [backend/AGENTS.md](../../backend/AGENTS.md) и [frontend/AGENTS.md](../../frontend/AGENTS.md) для изменяемой области.

## Done when

- [ ] Visitor A не читает/меняет ресурсы B, включая SSE и versioned source.
- [ ] Expired/wrong-audience/forged token отвергается, healthcheck не сломан.
- [ ] Guest не может POST /api/products или admin import; права проверяются сервером, не UI.
- [ ] REST и SSE проходят с одной identity; postMessage/URL не даёт подменить owner.
- [ ] Проверки выполнены на своей ветке; PR содержит результат проверок и известные ограничения.

## Проверка и передача

При backend-изменениях: `cd backend && ./gradlew build`; при frontend: `cd frontend && npm run build && npm run lint`. API изменился → поднять backend и выполнить `cd frontend && npm run gen`. Помимо сборки воспроизвести перечисленные выше позитивные и негативные сценарии; mocks портов не считаются финальной продуктовой приёмкой.

Проверку нового clean/upgrade DB проводить в отдельном Compose project/volume. Общую demo-БД не сбрасывать. Результаты бизнес-сценариев после интеграции входят в QA-01/QA-02; нагрузка — PERF-02.

**Передать:** D2: TrustedScope и authorization helpers; D3: bootstrap/refresh/logout контракт и origin policy.

**Evidence после выполнения:** заполнить commit/PR, команды, ссылки на отчёты и фактический результат; до подтверждения всех критериев статус остаётся in_progress.

**Текущий handoff:** [реализация D1 и открытые проверки](../../docs/api/d1-handoff.md). Статус `in_progress`; результаты final integration run пока pending.
