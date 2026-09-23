# План реализации ekt.kz: 3 разработчика, 32 задачи

Основной продуктовый и архитектурный документ: [единое ТЗ](../docs/ekt-assistant-spec.md). Исходное партнёрское [ТЗ](../docs/ТЗ_ИИ-ассистент_ekt.kz.md) сохранено без изменений. Здесь — порядок реализации и рабочие карточки; все новые задачи имеют `status: todo`.

Конечный результат: пользователь открывает встроенный desktop/mobile чат, получает проверенные товары/аналоги/условия, прикладывает Excel/Word/PDF/JPEG, проверяет распознанные позиции, отдельно подтверждает точное предложение и открывает актуальную корзину. Всё проходит через Docker Compose. 5k RPS — отдельная обязательная приёмка для заявления о выполнении полного NFR, а не следствие успешного запуска контейнеров.

## Как взять задачу

1. Прочитать единое ТЗ, корневой и профильный AGENTS.md, свою карточку и её зависимости.
2. Создать короткую ветку `codex/<task-id>-<description>` от актуального интегрированного состояния в отдельном worktree. Неприменённые миграции и занятые порты согласовать заранее.
3. Перевести `todo → in_progress`; зафиксировать API/port изменения до реализации. Для публичного API: springdoc → generated SDK, без ручных HTTP-типов.
4. Выполнить шаги и негативные сценарии, указанные в карточке. Обновить env/application/compose одним согласованным PR; собрать нужные части.
5. Передать соседнему разработчику перечисленные контракты/fixtures/evidence. `done` только после фактической проверки карточки; итоговый продукт принимается через QA/REL gates.

Объём S/M/L относительный: S — небольшой согласовательный/локальный блок, M — функциональный модуль, L — несколько связанных реализаций и интеграционных проверок. Это не обещание времени. При необходимости L разбивается на маленькие PR без изменения критерия готовности.

## Роли и владение

**D1 — backend чата, сессии, tool orchestration, безопасная корзина, общие лимиты.** 11 задач. Единственный интегратор публичного OpenAPI/Java shared contracts, migration registry, `backend/build.gradle`, общих `application.yml` и security beans. D2 готовит изменения зависимостей/схемы своих модулей в PR, D1 координирует merge.

**D2 — каталог, точные остатки, аналоги, RAG, ingestion и распознавание вложений.** 11 задач. Владеет `domain/catalog|documents|attachments`, `ai/catalog|rag|attachments`, соответствующими controllers, `data/` и quality fixtures. Cart mutation не вызывает; отдаёт D1 проверенные данные через ports.

**D3 — frontend/widget, generated SDK, Compose, CI и продуктовые проверки.** 10 задач. Владеет `frontend/`, package lock, Docker/compose/test overrides, `.env.example`, test runners и README запуска. Backend application counterpart env согласуется с D1 в том же PR.

Это разделение по ответственности, а не запрет помощи. Менять общий файл параллельно без координации нельзя. В domain файлах D2 может работать независимо от D1 после передачи ports; frontend работает на generated fixtures до живых endpoints.

## Что каждый делает сразу

- D1: FOUND-01 — ранний schema/ports PR; параллельно обсуждению полей быстро фиксирует NFR-01. Не ждёт реализации каталога и не пишет всю бизнес-логику до публикации OpenAPI.
- D2: DATA-01 — sample SKU, офферы, FAQ и валидные файлы; передаёт schema draft каталога/вложений для общего gate. Не ждёт готового чата.
- D3: OPS-01 — isolated Compose и runtime foundation; может создать shell `/widget` и host page без HTTP-типов. После FOUND-01 сразу UI-01, затем UI ветка на fixtures.

## Общий контракт и передача G0 → G1

FOUND-01 — небольшой общий первый merge, совместно проверяемый D1/D2/D3. Первый отдельный contract-only PR поставляет signatures DTO/controllers, trusted ports, springdoc snapshot и ошибки/SSE union. Этого достаточно для завершения FOUND-01 и немедленного старта UI-01: frontend не ждёт SQL всего продукта. Schema-only migrations готовятся следующими ранними PR D1/D2 из соответствующих domain-задач по реестру ниже. Stubs допустимы только в contract/test profile, никогда незаметно в live. DATA-01 отдаёт содержательные fixtures; OPS-01 обеспечивает startup.

Frontend команда из `frontend/` после появления snapshot:

```bash
npm run gen -- --input ../docs/api/openapi.json
npm run build
npm run lint
```

`docs/api/openapi.json` должен быть получен из springdoc в FOUND-01; сейчас этот будущий файл не создаётся вручную. Обычная генерация остаётся `npm run gen` с запущенным backend. После ЛЮБОГО изменения API автор PR регенерирует SDK; D3 после объединения веток заново генерирует итог, а не разрешает generated конфликт руками.

Mock fixtures `satisfies GeneratedDto` проходят wire-schema проверки и вызываются через настоящий generated SDK. Финальная QA-01 отключает browser mocks и использует реальный backend; подменяются только внешние gateways по явному test mode.

## Реестр миграций и порядок merge

V1 и V2 уже существуют. Следующие назначения **предлагаемые и ещё не созданы**; сначала проверить актуальный git/main, если кто-то уже добавил V3 — сдвинуть весь неприменённый план до выдачи номеров.

- V3: conversation/message/run/identity state — D1 draft.
- V4: расширение существующего products, offers, stock и catalog import metadata — D2 draft.
- V5: KB versions/chunks/jobs и private attachments/reviews — D2 draft.
- V6: proposals/cart operations и stateful sample cart — D1 draft.
- V7: result sets/dialogue state/tool call persistence и необходимые межмодульные constraints — D1, schema review D2.

D1 интегрирует эту последовательность ранними schema-only PR из CHAT-01/CAT-01/ATT-01/RAG-01/CART-01, включая FK ordering/DTO naming. **Контракт и frontend не ждут полного V3…V7.** Domain-код можно писать и тестировать на ports/fakes параллельно. Перед применением очередной миграции в общем integration DB подхватывается весь непрерывный согласованный prefix; до совместного domain E2E все worktrees используют одинаковый schema baseline. В выделенной одноразовой БД draft-ветки допускается пересоздание только её собственного volume после появления более ранней миграции; shared DB так не исправляется. Изменение плана до применения допустимо; после применения SQL не редактировать. Следующую V8+ выдаёт D1 по фактическому merge order. Не резервировать далёкие диапазоны по разработчикам и не включать Flyway outOfOrder ради обхода конфликтов.

Миграции не вызывают OpenAI и не загружают удалённые файлы. Sample business seed/index — отдельный idempotent initializer. Проверять и чистую изолированную БД, и upgrade с существующей V2.

## Параллельные этапы и gates

Номер `wave` в карточке — ориентир начала/интеграции, не требование ждать завершения всех задач предыдущей строки. Машинное `depends_on` содержит минимальные зависимости для старта. Domain port fakes позволяют раннюю реализацию, но общий feature считается готовым только после живого подключения на G4/G5.

**G0 — одновременно подготовка.** D1 FOUND-01/NFR-01, D2 DATA-01, D3 OPS-01. Выход: ранний contract-only ports/schema snapshot+fixtures и изолированное окружение; SQL baseline интегрируется следующим согласованным шагом, не блокируя UI.

**G1 — три независимых потока.** D1 AUTH-01 → CHAT-01; D2 CAT-01 и ATT-01; D3 UI-01 и shell/embed. Выход: принимаем run, данные имеют жизненный цикл, frontend работает по контракту.

**G2 — реализация основ.** D1 CHAT-02/03 на fake ports; D2 CAT-02/03, RAG-01, ATT-02/03; D3 UI-02/05. Выход: streaming и typed results, работающие data/read-tools, real iframe host. D2 выполняет свои несколько модулей последовательно небольшими PR, не предполагается дополнительный скрытый разработчик.

**G3 — действия и пользовательские сценарии.** D1 CHAT-04 → CART-01/02; D2 CAT-04 → ATT-04; D3 UI-03/04. Выход: аналог/вложение → выбранный состав → отдельная proposal card, до согласия корзина не меняется.

**G4 — соединить реализации.** D1 CART-03/PERF-01 подключает реальные D2 Catalog/Stock/Knowledge/Attachment ports; D2 QA-02 проверяет качество; D3 OPS-02 подключает весь UI к backend. На этом gate mocks остаются только на внешних провайдерах в test mode. Каждый разработчик исправляет свой домен.

**G5 — принять продукт и нагрузку.** D3 QA-01, затем PERF-02 по данным QA-02, при поддержке D1/D2 для исправлений. Выход: AC-1…17 с evidence, а не только отдельные unit tests.

**G6 — передача.** D3 REL-01: итоговый checkout, команды, source provenance, env, reports и честный статус внешних интеграций.

Условия handoff:

- D1→D2: trusted identity, port signatures, errors, job/run context. D2→D1: stable products/offers, typed tool results, reviewed attachments, versioned sources; контракты важнее полного readiness реализации.
- D1→D3: schema, SSE events, proposal/confirm/status semantics. D2→D3: fixtures всех состояний и source coordinates через shared DTO.
- D3→D1/D2: воспроизводимый browser/API failure, ожидаемый AC и trace ID. Общая финализация не означает, что D3 самостоятельно исправляет backend/data баги.

## Общая приёмка карточки

- Backend `./gradlew build`; frontend `npm run build`/`npm run lint` по изменяемой части. Для соответствующих задач meaningful domain/integration/browser tests из критериев.
- DTO + Jakarta Validation + MapStruct; generated TS не редактируется руками. Provider I/O не находится в DB transaction; parsers не блокируют event loop.
- Новые env в `.env.example` и compose одновременно с application; имена сервисов и browser/build-time VITE контракт сохранены.
- Изменённый demo путь проверен. API изменён → client regeneration. Секретов в git нет.
- Коммит Conventional Commits и небольшой reviewable PR. Main только с зелёными проверками, force push запрещён.
- Shared DB/ports не сбрасываются. Clean tests создают свой project/volume и безопасно очищают только его.

## Финальный запуск и значение «готово»

Существующий контракт: `docker compose up -d` запускает БД; `docker compose --profile full up -d --build` — весь продукт после реализации. Для live режима заполнить `OPENAI_API_KEY`, для data/cart можно использовать synthetic/stateful sample adapters.

OPS-02 должен поставить:

```bash
./scripts/test-compose.sh                    # offline integration: настоящие domains/parsers/UI, fake gateways
./scripts/test-compose.sh --live-ai          # настоящий OpenAI, sample catalog + безопасный mock cart
./scripts/test-compose.sh --load-profile 5000 # выделенный load проект и утверждённый mix
```

Скриптов и дополнительных test compose файлов пока нет: они являются результатом конкретных задач. Mock режим не требует платного key, default real profile продолжает требовать его. Live AI не включает автоматически реальные partner cart writes.

Продуктовый отчёт различает: (1) functional Compose build AC-1…14/17, (2) качество AC-15 на real providers, (3) partner adapter compatibility, (4) high-load AC-16. При отсутствии API/квот внешние пункты не становятся pass. Заявление «всё ТЗ выполнено» требует evidence по всем обязательным критериям.

Первый 5-часовой срез можно показать через exact SKU → карточка → предложение → отдельное подтверждение → stateful sample cart + один FAQ. Это промежуточная демонстрация; остальные форматы, аналоги, mobile, recovery и нагрузка не вычёркиваются из конечного плана.

## Карточки по разработчикам
### D1 — Чат, identity, agent и корзина
- [ ] [FOUND-01 — Первый API-контракт, Java ports и схема handoff](00-foundation/FOUND-01-contracts-and-ports.md) — G0, M.
- [ ] [NFR-01 — Профиль 1k–5k RPS и измеримые условия релиза](00-foundation/NFR-01-workload-and-release-gates.md) — G0, S.
- [ ] [AUTH-01 — Visitor/partner identity и изоляция ресурсов](01-identity/AUTH-01-session-and-ownership.md) — G1, M.
- [ ] [CHAT-01 — Разговоры, история, durable runs и идемпотентность](02-chat/CHAT-01-history-and-runs.md) — G1, L.
- [ ] [CHAT-02 — OpenAI agent с ограниченным tool loop](02-chat/CHAT-02-agent-tools.md) — G2, L.
- [ ] [CHAT-03 — SSE, reconnect и защита от устаревших событий](02-chat/CHAT-03-sse-recovery.md) — G2, M.
- [ ] [CHAT-04 — Продолжение подбора и привязка пользовательского выбора](02-chat/CHAT-04-dialogue-context.md) — G3, M.
- [ ] [CART-01 — Предложения корзины без изменения корзины](05-cart/CART-01-immutable-proposals.md) — G3, M.
- [ ] [CART-02 — Отдельный Confirm Gate и проверка явного согласия](05-cart/CART-02-confirmation-gate.md) — G3, L.
- [ ] [CART-03 — Stateful Cart API, атомарность и неизвестный исход](05-cart/CART-03-cart-adapters-and-reconciliation.md) — G4, L.
- [ ] [PERF-01 — Общие лимиты, replay нескольких реплик и метрики](08-platform/PERF-01-limits-events-and-metrics.md) — G4, L.

### D2 — Каталог, RAG и распознавание
- [ ] [DATA-01 — Синтетические данные и ожидаемые сценарии](00-foundation/DATA-01-sample-data-contract.md) — G0, M.
- [ ] [CAT-01 — Развитие каталога V2, безопасный импорт и индексация](03-catalog/CAT-01-import-and-schema.md) — G1, L.
- [ ] [ATT-01 — Приватные вложения, storage и durable processing](06-attachments/ATT-01-upload-and-jobs.md) — G1, M.
- [ ] [CAT-02 — Точный, лексический и семантический поиск](03-catalog/CAT-02-search-and-comparison.md) — G2, M.
- [ ] [CAT-03 — Актуальные цены, остатки и склады](03-catalog/CAT-03-offers-and-stock.md) — G2, M.
- [ ] [RAG-01 — Условия покупки, versioned RAG и источники](04-knowledge/RAG-01-terms-and-sources.md) — G2, L.
- [ ] [ATT-02 — Excel и Word: строки, количества и координаты](06-attachments/ATT-02-office-extraction.md) — G2, M.
- [ ] [ATT-03 — PDF, OCR и распознавание товара на JPEG](06-attachments/ATT-03-pdf-ocr-and-photo.md) — G2, L.
- [ ] [CAT-04 — Совместимые аналоги и варианты частичной поставки](03-catalog/CAT-04-analogs-and-fulfillment.md) — G3, M.
- [ ] [ATT-04 — Сопоставление каталогу и ручная проверка строк](06-attachments/ATT-04-matching-and-review.md) — G3, M.
- [ ] [QA-02 — Качество поиска, аналогов, RAG и распознавания](09-quality/QA-02-rag-and-recognition-evaluation.md) — G4, M.

### D3 — Виджет, инфраструктура и приёмка
- [ ] [OPS-01 — Изолированный Compose и основа режимов запуска](08-platform/OPS-01-compose-foundation.md) — G0, M.
- [ ] [UI-01 — Generated клиент, auth и HTTP/SSE fixtures](07-widget/UI-01-client-and-fixtures.md) — G1, M.
- [ ] [UI-02 — Чат, история, streaming и восстановление](07-widget/UI-02-chat-and-streaming.md) — G2, L.
- [ ] [UI-05 — Встраивание script/iframe и мобильный виджет](07-widget/UI-05-embed-and-mobile.md) — G2, M.
- [ ] [UI-03 — Товары, аналоги, подтверждение и актуальная корзина](07-widget/UI-03-products-proposals-and-cart.md) — G3, L.
- [ ] [UI-04 — Загрузка файлов и проверка распознанных позиций](07-widget/UI-04-attachments-review.md) — G3, M.
- [ ] [OPS-02 — Полный Compose-продукт, seed и containerized test runner](08-platform/OPS-02-complete-product-compose.md) — G4, L.
- [ ] [QA-01 — Приёмка AC-1…14 и AC-17 через настоящий виджет](09-quality/QA-01-end-to-end-acceptance.md) — G5, L.
- [ ] [PERF-02 — Нагрузка 1k→3k→5k, soak и отказоустойчивость](08-platform/PERF-02-load-and-failure-tests.md) — G5, L.
- [ ] [REL-01 — Финальная интеграция, документация и поставка](09-quality/REL-01-release-handoff.md) — G6, M.

## Функциональные папки

- `00-foundation/`: [FOUND-01](00-foundation/FOUND-01-contracts-and-ports.md) · [DATA-01](00-foundation/DATA-01-sample-data-contract.md) · [NFR-01](00-foundation/NFR-01-workload-and-release-gates.md).
- `01-identity/`: [AUTH-01](01-identity/AUTH-01-session-and-ownership.md).
- `02-chat/`: [CHAT-01](02-chat/CHAT-01-history-and-runs.md) · [CHAT-02](02-chat/CHAT-02-agent-tools.md) · [CHAT-03](02-chat/CHAT-03-sse-recovery.md) · [CHAT-04](02-chat/CHAT-04-dialogue-context.md).
- `03-catalog/`: [CAT-01](03-catalog/CAT-01-import-and-schema.md) · [CAT-02](03-catalog/CAT-02-search-and-comparison.md) · [CAT-03](03-catalog/CAT-03-offers-and-stock.md) · [CAT-04](03-catalog/CAT-04-analogs-and-fulfillment.md).
- `04-knowledge/`: [RAG-01](04-knowledge/RAG-01-terms-and-sources.md).
- `05-cart/`: [CART-01](05-cart/CART-01-immutable-proposals.md) · [CART-02](05-cart/CART-02-confirmation-gate.md) · [CART-03](05-cart/CART-03-cart-adapters-and-reconciliation.md).
- `06-attachments/`: [ATT-01](06-attachments/ATT-01-upload-and-jobs.md) · [ATT-02](06-attachments/ATT-02-office-extraction.md) · [ATT-03](06-attachments/ATT-03-pdf-ocr-and-photo.md) · [ATT-04](06-attachments/ATT-04-matching-and-review.md).
- `07-widget/`: [UI-01](07-widget/UI-01-client-and-fixtures.md) · [UI-02](07-widget/UI-02-chat-and-streaming.md) · [UI-03](07-widget/UI-03-products-proposals-and-cart.md) · [UI-04](07-widget/UI-04-attachments-review.md) · [UI-05](07-widget/UI-05-embed-and-mobile.md).
- `08-platform/`: [OPS-01](08-platform/OPS-01-compose-foundation.md) · [PERF-01](08-platform/PERF-01-limits-events-and-metrics.md) · [OPS-02](08-platform/OPS-02-complete-product-compose.md) · [PERF-02](08-platform/PERF-02-load-and-failure-tests.md).
- `09-quality/`: [QA-01](09-quality/QA-01-end-to-end-acceptance.md) · [QA-02](09-quality/QA-02-rag-and-recognition-evaluation.md) · [REL-01](09-quality/REL-01-release-handoff.md).

## Источники и актуальность

Нормативный для реализации продуктовый текст — [ekt-assistant-spec.md](../docs/ekt-assistant-spec.md), workflow — AGENTS.md. Старые `ai-chat-architecture.md` и `ai-chat-backlog.md` заменены ссылками на этот документ и новые карточки, чтобы не осталось конкурирующего TXT-only/без-cart scope. Исходное ТЗ не удаляется и не изменяется.

Названия/IDs задач стабильны; при уточнении требования обновить spec, affected карточки и acceptance mapping одним PR. Изменения в поддержке версий/model API проверять на фактически закреплённых зависимостях, не копировать latest примеры без compatibility spike.
