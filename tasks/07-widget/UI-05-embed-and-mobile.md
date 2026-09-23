---
id: UI-05
owner: D3
status: done
wave: 2
size: M
depends_on: ["UI-01"]
---

# UI-05 — Встраивание script/iframe и мобильный виджет

**Исполнитель:** D3. **Этап:** G2. **Объём:** M.
**Зависимости для старта:** [UI-01](../07-widget/UI-01-client-and-fixtures.md)
**Покрытие:** AC-12/13; NFR совместимость.

## Goal

Запускать чат на внешней host page без переделки сайта.

## Context

ТЗ требует desktop/mobile и embed; текущий frontend — отдельная SPA.

Основные решения: [единое ТЗ](../../docs/ekt-assistant-spec.md). Общий workflow и финальные gates: [tasks/README.md](../README.md).

## План реализации

1. Сделать /widget route и versioned script loader, который создаёт iframe/launcher в существующем frontend build.
2. Добавить настоящую host-demo page на отдельном origin/port в Compose для проверки embedding, плюс local cart route.
3. Согласовать AUTH-01 bootstrap: parent origin/source allowlist, validated postMessage shape и audience; token не передаётся в URL.
4. Обеспечить session reload/refresh в iframe без единственной зависимости от third-party cookies; forged parent не может назначить owner/cart.
5. Responsive layout проверять390×844 и desktop; virtual keyboard, scroll/composer/upload, focus return, Escape/labels.
6. Настроить embedding CSP/frame-ancestors/allowed origins и asset paths через конфигурацию; host-domain стили не ломают widget.

## Область изменений

`frontend/public/embed/v1/widget.js`; `frontend/src/widget/`; `frontend/src/pages/WidgetPage.tsx`; `host-demo/`; `frontend/nginx.conf`; `frontend/tests/embed-bridge.test.ts`

## Constraints

- Не создавать второй frontend codebase без необходимости.
- Mobile обязательный scope по ТЗ, несмотря на demo-default AGENTS.
- postMessage origin и event.source проверяются вместе; credentials/PII в URL запрещены.
- Соблюдать [AGENTS.md](../../AGENTS.md), [backend/AGENTS.md](../../backend/AGENTS.md) и [frontend/AGENTS.md](../../frontend/AGENTS.md) для изменяемой области.

## Done when

- [x] Host-demo script открывает iframe, chat сохраняется при close/reopen/reload.
- [x] Mobile keyboard не закрывает composer/confirm; focus/scroll доступны.
- [x] Недоверенный origin/message не получает session или Cart action.
- [x] Виджет и cart link работают в production frontend image, не только Vite dev.
- [x] Проверки выполнены на своей ветке; PR содержит результат проверок и известные ограничения.

## Проверка и передача

При backend-изменениях: `cd backend && ./gradlew build`; при frontend: `cd frontend && npm run build && npm run lint`. API изменился → поднять backend и выполнить `cd frontend && npm run gen`. Помимо сборки воспроизвести перечисленные выше позитивные и негативные сценарии; mocks портов не считаются финальной продуктовой приёмкой.

Проверку нового clean/upgrade DB проводить в отдельном Compose project/volume. Общую demo-БД не сбрасывать. Результаты бизнес-сценариев после интеграции входят в QA-01/QA-02; нагрузка — PERF-02.

**Передать:** OPS-02: host-demo service/route и browser origins; QA-01: desktop/mobile cross-origin tests.

## Evidence

**Команды:** `cd frontend && npm run test` → 30 passed; `npm run lint` → 0 errors (2 pre-existing hook warnings); `npm run build` → ok, `dist/embed/v1/widget.js` present.

**Порты:** frontend `5173`, host-demo `5180`, API `8080`.

**Ручной smoke:**
1. `docker compose --profile full up -d --build` → http://localhost:5180 → кнопка «Чат» → iframe `/widget`.
2. Close/reopen лаунчера: iframe остаётся в DOM, demo history в sessionStorage iframe origin.
3. Dev: `npm run dev` + `npx serve ../host-demo -p 5180` (или compose host-demo против Vite на 5173 — loader URL в `host-demo/index.html`).

**Тесты:** `frontend/tests/embed-bridge.test.ts` — allowlist origin+source; reject token/cartId/forged parent.

**Known limits:** полный Playwright cross-origin/mobile keyboard E2E → QA-01/OPS-02; server visitor JWT bootstrap → AUTH-01; host-demo script URL захардкожен на `localhost:5173` для локального proof (не CDN).
