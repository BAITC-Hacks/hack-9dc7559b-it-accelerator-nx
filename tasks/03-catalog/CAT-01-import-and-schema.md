---
id: CAT-01
owner: D2
status: done
wave: 1
size: L
depends_on: ["FOUND-01", "DATA-01"]
acceptance_depends_on: ["AUTH-01"]
---

# CAT-01 — Развитие каталога V2, безопасный импорт и индексация

**Исполнитель:** D2. **Этап:** G1. **Объём:** L. Все задачи обязательны для полного scope; отметка todo означает, что реализация не выполнена.
**Зависимости для старта:** [FOUND-01](../00-foundation/FOUND-01-contracts-and-ports.md), [DATA-01](../00-foundation/DATA-01-sample-data-contract.md)
**Покрытие:** FR-1; AC-1/8/17.

**Дополнительно для итоговой приёмки:** подключить [AUTH-01](../01-identity/AUTH-01-session-and-ownership.md) и проверить HTTP-доступ к импорту с разрешённой и запрещённой identity. Реализацию на TrustedScope fakes можно начать после FOUND-01; они не закрывают проверку admin-only доступа.

## Goal

Развить существующую products/HNSW схему до versioned каталога с качественными коммерческими данными.

## Context

ProductSearchService использует hashCode ID, boolean stock, embedding внутри @Transactional и расположен в web/.

Основные решения: [единое ТЗ](../../docs/ekt-assistant-spec.md). Общий workflow и финальные gates: [tasks/README.md](../README.md).

## План реализации

1. Подготовить schema draft для общего FOUND-01 gate: stable ID/unique normalized article в scope, specs/cert refs, unit/minimum/step, source version и offers/warehouse rows.
2. Сохранить имеющиеся IDs/данные, выявить article duplicates до unique index; hash Aa/BB не должен перезаписывать другой продукт. Unknown quantity из stock boolean не превращать в 0/1.
3. Перенести бизнес-логику из web/ProductSearchService в domain/ai, controller оставить DTO boundary; описать эволюцию старого upsert/search и регенерировать SDK.
4. Реализовать admin import job и idempotent sample initializer; extract/embedding вне DB transaction, publish only ready version с CAS.
5. Catalog/embedding updates versioned, failed batch не ломает активные данные; fake/live vector spaces разделены test profile/volume.
6. Добавить admin-only imports и job status; existing public product write закрыть, schemas/validation/specs errors сделать явными.

## Область изменений

backend/…/domain/catalog/; ai/catalog/; web/catalog/; db/migration/V3+; data/; src/test/…/catalog/

Пути с многоточием обозначают предлагаемые package/file locations, а не уже существующие файлы. Публичный контракт, env и номера миграций согласовать по ownership в README.

## Constraints

- V1/V2 уже существуют; только V3+ из registry.
- Не использовать hashCode для нового PK; SKU сохраняет нули/значимые символы.
- Provider call вне транзакции; load/init не требует ручных SQL.
- Соблюдать [AGENTS.md](../../AGENTS.md), [backend/AGENTS.md](../../backend/AGENTS.md) и [frontend/AGENTS.md](../../frontend/AGENTS.md) для изменяемой области.

## Done when

- [x] Upgrade V2 и clean DB проходят миграции; старые product IDs сохранены.
- [x] Repeated import не дублирует товар; hash collision fixture не теряет данные.
- [x] Stock true/false без quantitative source отмечен unknown quantity, а не выдуманным числом.
- [x] Seed запускается повторно и ready catalog доступен после container restart.
- [x] Проверки выполнены на своей ветке; PR содержит результат проверок и известные ограничения.

## Проверка и передача

При backend-изменениях: `cd backend && ./gradlew build`; при frontend: `cd frontend && npm run build && npm run lint`. API изменился → поднять backend и выполнить `cd frontend && npm run gen`. Помимо сборки воспроизвести перечисленные выше позитивные и негативные сценарии; mocks портов не считаются финальной продуктовой приёмкой.

Проверку нового clean/upgrade DB проводить в отдельном Compose project/volume. Общую demo-БД не сбрасывать. Результаты бизнес-сценариев после интеграции входят в QA-01/QA-02; нагрузка — PERF-02.

**Передать:** D1/D3: стабильные Products/Offers DTO и sample IDs; OPS-02: initializer/readiness command.

**Evidence после выполнения:** заполнить commit/PR, команды, ссылки на отчёты и фактический результат; до выполнения статус остаётся todo.

## Evidence CAT-01 — 23.09.2026

Ветка `codex/cat-01-catalog-import-and-schema`, один коммит. Реализация выполнена;
карточка остаётся `in_progress` по собственному правилу `acceptance_depends_on`:
admin-доступ проверен временным токеном, а не настоящей identity из AUTH-01.

### Что сделано

- **Миграция V3** (`backend/src/main/resources/db/migration/V3__catalog_versions_offers_stock.sql`):
  `catalog_versions`, `product_versions`, `product_offers`, `warehouse_stock`,
  `catalog_import_jobs`, `catalog_legacy_products`. `products` стала таблицей
  стабильной идентичности (id + артикул + нормализованный артикул), содержимое
  уехало в версии. V1/V2 не изменялись.
- **Номер миграции:** выдан V3, а не V4 из первоначального реестра — в `main` не
  было ни одной миграции D1, и дыра перед применённой V4 сломала бы Flyway без
  `outOfOrder`. Реестр в [tasks/README.md](../README.md) сдвинут: V4 — chat/identity.
- **Бизнес-логика из `web/` перенесена** в `domain/catalog` (+ `ai/catalog` для
  векторов); `web/catalog` остался DTO-границей. `ProductSearchService`/
  `ProductSearchController` удалены.
- **Эволюция публичного контракта:** `GET /api/products/search` сохранён, но
  отдаёт объект с версией каталога и читает только активную версию; добавлен
  `GET /api/products/{article}` (точный поиск без вызова модели);
  **`POST /api/products` удалён** — запись только через админский импорт.
  Добавлены `POST /api/admin/catalog/imports` и `GET /api/admin/jobs/{id}`.
- **Импорт:** валидация → embeddings **вне транзакции** → короткая транзакция
  записи → публикация версии CAS. Идемпотентность по `Idempotency-Key`,
  очередь на одном воркере, статус задания с кодами ошибок и предупреждений.
- **Стартовый seed** (`SampleCatalogInitializer`) читает
  `data/sample_catalog/products.json` — тот же файл, что проверяет валидатор
  DATA-01; повторный запуск идемпотентен по хешу содержимого и пространству векторов.
- **Пространства векторов разделены:** `live:<model>:1536` и
  `fake:deterministic-v1:1536` пишутся в версию; поиск по чужому пространству
  отдаёт 503, а не «похожие» результаты.
- **Сквозная правка `build.gradle`:** springdoc приносил
  `swagger-annotations-jakarta:2.2.29`, Spring AI — `swagger-annotations:2.2.25`
  с тем же пакетом. `/v3/api-docs` падал с `NoSuchMethodError`
  `Parameter.validationGroups()`; дубликат исключён. Это общий файл D1 — нужно
  подтвердить при merge.

### Команды и результат

| Проверка | Команда | Результат |
| --- | --- | --- |
| Сборка backend | `cd backend && ./gradlew build --no-daemon` | PASS, 24 теста |
| Сборка frontend | `cd frontend && npm run build && npm run lint` | PASS |
| Регенерация SDK | `cd frontend && npm run gen` (backend на :8080) | 5 операций, ping + 4 каталога |
| Чистая БД | отдельный контейнер `hackalem-cat01-db` (порт 5533) | V1→V2→V3 + seed 36 товаров, индекс READY |
| Upgrade с V2 | отдельный контейнер `hackalem-cat01-upgrade` (5534), `SPRING_FLYWAY_TARGET=2` → данные V2 → рестарт | V3 применена, старые id сохранены |
| Полный продукт | `docker compose --profile full up -d --build` | db/backend/frontend healthy |
| Демо-путь в контейнерах | `GET /api/products/000001`, поиск «автомат на 16 ампер» | id `1001`, `1500.00`, `12 pcs`; в выдаче первым `000001` |
| Рестарт контейнера | `docker compose restart backend` | seed пропущен, версия одна, каталог отвечает |

В compose каталог проиндексирован **живой** моделью:
`vector_space = live:text-embedding-3-small:1536`, `embedding_status = READY` —
то есть путь с настоящим ключом тоже проверен, а не только offline-режим.

Общая demo-БД не сбрасывалась: на ней доехали V2 и V3 и один раз отработал seed
(это штатный путь запуска). Проверки чистой БД и upgrade шли в отдельных
контейнерах с собственными volume, которые после прогона удалены.

### Воспроизведённые сценарии

- **AC-1:** `GET /api/products/000001` → id `1001`, specs C16/1P/230V, цена
  `1500.00 KZT`, остаток `12` со склада `ALA`, сертификат. Недоступный склад
  `CLOSED` (999) показан отдельной строкой и **не** прибавлен к доступному.
- **AC-8:** `GET /api/products/999999` → 404 `PRODUCT_NOT_FOUND`,
  `substituted: false`; похожий товар не подставляется.
- **Hash-коллизия:** `Aa` → id `1035`, `BB` → id `1036` — разные товары
  (`"Aa".hashCode() == "BB".hashCode()`, но id больше не выводится из hashCode).
- **Дробное количество:** `000013` → `unit=m`, минимум/шаг `0.5`, остаток `37.5`.
- **Неизвестный остаток:** `000006` → `UNKNOWN`, `availableQuantity: null`.
- **Нулевой остаток:** `000005` → `OUT_OF_STOCK`, ровно `0`.
- **Legacy boolean → unknown:** строки V2 после upgrade получили статус `UNKNOWN`
  и `NULL` количество; исходный флаг сохранён в `catalog_legacy_products`.
- **Сохранение id:** legacy-товар с артикулом `000001` и id `777001` сохранил
  свой id, а `1001` из выгрузки записан как `supplier_id`.
- **Дубли артикулов V2:** второй товар с тем же нормализованным артикулом
  помечен `QUARANTINED` со ссылкой `duplicate_of`; строка не удалена.
- **Повторный импорт:** три импорта того же файла → 36 товаров, версий 3,
  активная одна. Повтор с тем же `Idempotency-Key` вернул то же задание.
- **Упавший батч:** импорт с `unit` длиннее колонки → задание `FAILED`,
  версия `FAILED`, активная версия и выдача каталога не изменились.
- **Отказ провайдера:** live-режим с неверным ключом → версия опубликована с
  `embedding_status=FAILED`, точный артикул отвечает, поиск отдаёт 503
  `CATALOG_INDEX_NOT_READY`.
- **Admin-доступ:** без токена и с чужим токеном → 403 `ADMIN_ACCESS_DENIED`;
  с разрешённым → 202; при пустом `ADMIN_API_TOKEN` → 503
  `ADMIN_ACCESS_NOT_CONFIGURED` (пустая переменная выключает endpoint, а не открывает).
- **Битый документ:** дубль артикула → 400 `CATALOG_IMPORT_INVALID` с кодом и
  путём `$.products[1].article`; каталог не изменён.

### Известные ограничения

1. **Admin-доступ временный.** Общий токен в `X-Admin-Token` (`ADMIN_API_TOKEN`)
   вместо роли у серверной identity. Итоговая приёмка пункта — после AUTH-01;
   `TrustedScopeResolver` для этого и выделен интерфейсом.
2. **Импорт — полный снимок каталога.** Товар, которого нет в новой выгрузке,
   не попадает в активную версию (данные и id при этом сохраняются). Частичных
   выгрузок и удаления товаров эта задача не вводит.
3. **Остатки принадлежат версии каталога.** Живое обновление цен/остатков между
   импортами — CAT-03; здесь остаток приходит снимком вместе с выгрузкой.
4. **Склады не суммируются.** Количество отдаётся только при одном доступном
   складе; в остальных случаях `quantityBasis` объясняет, почему числа нет.
   Политику отгрузки задаёт CAT-03/CAT-04.
5. **Поиск остался семантическим.** Точный артикул теперь отдельным endpoint без
   embedding, но лексический/параметрический поиск и ре-ранк — задача CAT-02.
6. **MapStruct использован только на входе.** Ответы мапятся вручную: у денег и
   количеств разные контракты сериализации, а статусы — производные значения.
7. **`docker-compose.yml` монтирует `./data` в backend** (`/app/data:ro`), потому
   что build context сервиса — `./backend`. Значение `CATALOG_SEED_LOCATION`
   согласовать с D3 в OPS-02.
8. **`container_name` в compose захардкожен**, поэтому второй Compose-проект
   для чистых прогонов поднять нельзя — проверки делались отдельными
   `docker run`. Параметризация имён — вопрос к OPS-01/OPS-02.

### Передача

- **D1/D3:** стабильные DTO `ProductResponse` / `OfferResponse` / `StockResponse` /
  `ImportJobResponse` в регенерированном `frontend/src/client`; sample IDs —
  `1001`…`1036` из DATA-01 при старте на чистой БД.
- **OPS-02:** каталог готовится сам при старте (`CATALOG_SEED_ENABLED`,
  `CATALOG_SEED_LOCATION`); отдельной команды инициализации не требуется.
  Для offline-прогонов — `CATALOG_EMBEDDING_MODE=fake`.


## Итоговая интеграция D2

Объединено в `codex/d2-remaining-services`: V5, D1 JWT/ports, актуальный OpenAPI и generated SDK.
Полная backend/frontend сборка и отдельный Compose HTTP runner проверены.
[Итоговый отчёт и failed quality gates](../../docs/reports/d2-integration.md).
