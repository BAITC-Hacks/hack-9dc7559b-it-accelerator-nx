---
id: CAT-01
owner: D2
status: todo
wave: 1
size: L
depends_on: ["FOUND-01", "DATA-01"]
---

# CAT-01 — Развитие каталога V2, безопасный импорт и индексация

**Исполнитель:** D2. **Этап:** G1. **Объём:** L. Все задачи обязательны для полного scope; отметка todo означает, что реализация не выполнена.
**Зависимости для старта:** [FOUND-01](../00-foundation/FOUND-01-contracts-and-ports.md), [DATA-01](../00-foundation/DATA-01-sample-data-contract.md)
**Покрытие:** FR-1; AC-1/8/17.

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

- [ ] Upgrade V2 и clean DB проходят миграции; старые product IDs сохранены.
- [ ] Repeated import не дублирует товар; hash collision fixture не теряет данные.
- [ ] Stock true/false без quantitative source отмечен unknown quantity, а не выдуманным числом.
- [ ] Seed запускается повторно и ready catalog доступен после container restart.
- [ ] Проверки выполнены на своей ветке; PR содержит результат проверок и известные ограничения.

## Проверка и передача

При backend-изменениях: `cd backend && ./gradlew build`; при frontend: `cd frontend && npm run build && npm run lint`. API изменился → поднять backend и выполнить `cd frontend && npm run gen`. Помимо сборки воспроизвести перечисленные выше позитивные и негативные сценарии; mocks портов не считаются финальной продуктовой приёмкой.

Проверку нового clean/upgrade DB проводить в отдельном Compose project/volume. Общую demo-БД не сбрасывать. Результаты бизнес-сценариев после интеграции входят в QA-01/QA-02; нагрузка — PERF-02.

**Передать:** D1/D3: стабильные Products/Offers DTO и sample IDs; OPS-02: initializer/readiness command.

**Evidence после выполнения:** заполнить commit/PR, команды, ссылки на отчёты и фактический результат; до выполнения статус остаётся todo.
