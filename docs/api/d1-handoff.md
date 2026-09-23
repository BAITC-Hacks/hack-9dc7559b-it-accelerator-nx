# D1 handoff: чат и корзина

**Статус: локальный backend проверен; продуктовая интеграция in_progress.** Ветка `codex/d1-chat-cart`. Ниже описано фактическое поведение кода, а не полная приёмка карточек. OpenAPI: [snapshot](openapi.json), runtime `/v3/api-docs`; краткие правила wire types — [API README](README.md). Нагрузка: [workload v1](../performance/workload.md).

## Быстрый локальный запуск

Java 21, Docker и Node требуются локально. Из корня репозитория, в отдельном Compose project:

```bash
docker compose -p hackalem-d1 -f scripts/d1-compose.yml up -d --wait
cd backend
SPRING_PROFILES_ACTIVE=contract SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:55432/hackalem REDIS_PORT=56379 PORT=18080 ./gradlew bootRun
```

Contract profile не вызывает OpenAI, создаёт две тестовые позиции (`DEMO-001`, `DEMO-002`) и включает visitor login. Порты 55432/56379 из `scripts/d1-compose.yml` отличаются от общей demo-БД. Этот файл поднимает **только DB и Redis**. Остановка без удаления данных: `docker compose -p hackalem-d1 -f scripts/d1-compose.yml down`.

В другом терминале:

```bash
curl --fail http://localhost:18080/actuator/health
curl --fail -X POST http://localhost:18080/auth/visitor-session
cd frontend
npm run gen -- --input ../docs/api/openapi.json
npm run build
npm run lint
```

Сохранить `accessToken` из ответа login и передавать `Authorization: Bearer …` в REST и fetch SSE. Токен не помещать в URL. Live profile использует настоящий OpenAI gateway, требует key и подключённых data ports; contract fixtures в live не включаются. Stateful sample cart — отдельный локальный adapter, не ekt.kz.

## Контракт для D3

| Действие | HTTP и ключевые условия |
|---|---|
| Сессия | `POST /auth/visitor-session`, `POST /auth/refresh`, `POST /auth/logout`. Refresh сохраняет principal/cart; logout отзывает сессию. В live visitor по умолчанию выключен. |
| История | `POST/GET /api/conversations`; `GET /api/conversations/{id}/messages?cursor=…&limit=30`. Cursor opaque. |
| Turn | `POST /api/conversations/{id}/messages`, body `{ "text": "DEMO-001" }`, обязательный `Idempotency-Key`; `202` отдаёт `messageId` и `runId`. Повтор с тем же body возвращает исходный run, другой body — conflict. |
| Run | `GET /api/runs/{id}`, `POST /api/runs/{id}/cancel`; финальный snapshot и история доступны после reconnect. |
| SSE | `GET /api/runs/{id}/events`, `Last-Event-ID: epoch:seq`; не перезапускает generation. `replay_unavailable` содержит snapshot, далее читать run/history. Дубликаты/старые epoch игнорировать. |
| Выбор | `GET/PATCH /api/conversations/{id}/state`, optimistic `expectedVersion`; `selectedIndices` — **с нуля**, обязательно привязаны к `resultSetId` сохранённого порядка. |
| Результаты | `GET /api/conversations/{id}/results/{resultId}`; карточки берутся из typed `products.result`, а не из текста модели. |
| Предложение | `POST /api/cart/proposals`, `Idempotency-Key`, body `conversationId`, `expectedStateVersion`, `resultSetId`, `lines` с article/unit/warehouse/addQuantity. Prepare не меняет корзину. |
| Подтверждение | `POST /api/cart/proposals/{id}/confirm`, `Idempotency-Key`, **точные** `revision`, `digest`, `origin: "button"` без text либо `origin: "user_text"` с явным согласием. |
| Корзина | `GET /api/cart`; `POST /api/cart/proposals/{id}/reject`; `GET /api/cart/operations/{id}` для reconciliation. |

SSE envelope: `eventId`, `runId`, `seq`, `epoch`, `type`, `schemaVersion`, discriminated `payload.kind`. Типы событий: `run.started`, `tool.status`, `message.delta`, `products.result`, `alternatives.result`, `sources.result`, `attachment.review`, `cart.proposal`, `run.completed`, `run.failed`, `run.cancelled` и `replay_unavailable`; точный schema — generated SDK. Decimal money/quantities, UUIDs и counters передаются строками, не JS number. Ошибки: `application/problem+json` с `code`, `correlationId`, `retryable`; 404 скрывает чужие ресурсы, 409 — stale/idempotency conflict, 429/503 — capacity/dependency failure.

Подтверждение относится только к показанному immutable proposal: отрицание, цитата или обычное сообщение чата согласия не дают. Допустимые `user_text`: «да», «добавь», «подтверждаю», «подтверждаю добавление». При `outcome_unknown` показывать ожидание и запрашивать operation status; новую мутацию не запускать. Новый состав/количество требует нового proposal и нового согласия.

Короткие реплики «нужно 20 штук», «сравни первые два», «дешевле» обновляют сохранённый quantity/выбор/budget; hard constraints сохраняются. Смена количества немедленно supersedes прежнее предложение. Полный семантический подбор/получение ограничений из свободной речи остаются предметом интеграции с D2 и live-AI проверки. `sources.result` передаёт проверенные excerpts с SourceRef; они также сохраняются в тексте истории.

Роль ADMIN выдаётся только UUID из серверного `ADMIN_PRINCIPAL_IDS` после проверки токена и сессии. Для локального оператора создать visitor session, добавить её principal UUID в конфигурацию backend и перезапустить backend; токен хранить приватно. Пустой allowlist никому не выдаёт admin. Параметры body/JWT, не подписанного сервером, роль не назначают.

## Контракт для D2

Все интерфейсы и DTO находятся в `backend/src/main/java/com/hackalem/domain/port/`. Реализации Spring beans подключаются по интерфейсу; provider I/O выполняется вне DB transaction.

| Port | Требование |
|---|---|
| `CatalogPort.search/getProduct` | `ProductResultSet` с устойчивым порядком, product/article и количественными offers; не игнорировать `SearchQuery.hardConstraints`. |
| `StockPort.getOffers` | Canonical base-unit `stockBucket`, unit/step, warehouse, decimal price/available, version, observedAt/expiresAt; подтверждение перепроверяет price/version/aggregate existing+added stock. |
| `AnalogsPort.find` | `AlternativePlan` с конкретными selections и различиями; никаких cart writes. |
| `KnowledgePort.retrieve` | Versioned `SourceChunk/SourceRef`, соблюдать `characterBudget`, source coordinates; текст рассматривается как untrusted data. |
| `AttachmentPort.getReviewedItems` | Только проверенные selections конкретных attachment ID/version, ACL по `TrustedScope`, координаты источников. |

`TrustedScope(principalId, cartId)` приходит от сервера после проверки JWT; owner/cart не брать из пользовательского body или модели. Cart mutation доступна отдельному confirm gate. Если data bean отсутствует, tools завершаются `*_source_unavailable`; фальшивые данные не подставляются в live. Contract `AnalogsPort` сейчас возвращает пустой список, AttachmentPort — 404: это не реализация D2.

Миграции: V1/V2 сохраняются; **V3__identity_chat_cart.sql содержит весь D1 baseline** (identity/conversations/messages/runs/events/results/tool calls/proposals/operations/sample cart). D2 следующие V4 catalog/stock, V5 KB/attachments; потом выдавать следующий номер по merge order. Применённые миграции не редактировать, дырок и outOfOrder не создавать.

## Что ещё не принято

- `PartnerIdentityPort` пока только интерфейс: проверенный partner exchange и adapter ekt.kz не подключены.
- HTTP Cart adapter реализован и проверен на контрактных ответах; реальный партнёр и его atomic/lookup guarantees не проверены. См. [протокол и gate](partner-cart.md). Sample recovery может безопасно продолжить durable intent по operation ID; remote recovery делает только lookup.
- SSE события durable и читаются из PostgreSQL; Redis Streams реплицируются, но HTTP replay сейчас читает PG. Высокая нагрузка и POST на A / stream на B / reconnect на C требуют отдельного теста.
- Model stream обрабатывается gateway, но произвольные model deltas не выводятся: worker публикует безопасный итоговый текст после tool loop. TTFT полезного текста и полноценное UX streaming ещё надо измерить и принять.
- Источники, аналоги и attachment review DTO есть; real D2 corpus/parsers/RAG и продуктовая QA-02 не подключены. Source events сами по себе не подтверждают качество ответа об условиях покупки.
- Общие RPM/TPM/concurrency и bounded workers есть; defaults рассчитаны на dev. `ContractLlm` мгновенный, capacity не доказывает. Prometheus endpoint защищён ADMIN; scrape доступ ещё нужно настроить.
- Live OpenAI, partner compatibility, widget E2E, 1k/3k/5k performance, отказоустойчивость нескольких реплик и здоровье внешнего деплоя **не проверены**.

## Evidence

Проверено 2026-09-23 на отдельном Compose project `hackalem-d1` и одноразовых Testcontainers. Общая demo-БД не изменялась.

| Проверка | Текущий evidence |
|---|---|
| Backend build + domain integration | PASS `cd backend && ./gradlew build --no-daemon`: 43 tests, 0 failures (29 core/DB/security, 14 HTTP adapter). HTML report: `backend/build/reports/tests/test/index.html`. |
| Flyway clean + upgrade V2→V3 | PASS: clean Testcontainers DB и отдельная upgrade DB сначала мигрирована до V2, затем до V3; старые migrations не менялись. |
| OpenAPI + SDK regeneration | PASS live `npm run gen`, frontend `npm run build` и `npm run lint`; discriminated union не создаёт циклические TS types. 6 actual fixtures проверены JSON Schema Draft 2020-12 по экспортированному OpenAPI. |
| API demo | PASS `python3 scripts/d1-smoke.py --base-url http://localhost:8080 --fixtures-dir docs/api/fixtures --export-schema`: login → turn → products → proposal → confirm → cart, history, SSE initial/replay, duplicate, чужая session, logout. |
| NFR | Только валидные planning JSON; нагрузка и quota evidence отсутствуют. |

Все 11 карточек D1 остаются `in_progress`, пока их критерии и соответствующие интеграционные evidence не выполнены.

Не реализованные D1 детали, которые нельзя считать закрытыми локальным smoke: проверка partner identity; полноценный streaming полезного model text; новый proposal внутри ответа на price/stock conflict (сейчас failed operation требует нового prepare); подтверждённые attachment/fulfillment варианты ещё требуют подключения D2; Redis Streams ещё не основной read path; автоматический scheduled reconciliation remote operations пока заменён status lookup клиента. Quota outage fail-closed реализован, high-load/soak/несколько реплик должны подтвердить PERF-02. Эти ограничения сохраняют явное состояние и не превращают неизвестный результат в успешную покупку.
