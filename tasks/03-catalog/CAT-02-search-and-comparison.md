---
id: CAT-02
owner: D2
status: in_progress
wave: 2
size: M
depends_on: ["CAT-01"]
---

# CAT-02 — Точный, лексический и семантический поиск

**Исполнитель:** D2. **Этап:** G2. **Объём:** M. Все задачи обязательны для полного scope; отметка todo означает, что реализация не выполнена.
**Зависимости для старта:** [CAT-01](../03-catalog/CAT-01-import-and-schema.md)
**Покрытие:** FR-1/7; AC-1/8/10.

## Goal

Искать без лишнего LLM/embedding вызова при точном артикуле и сохранять смысл сравнения.

## Context

Сейчас любой query вызывает embedding и выдаёт сокращённую карточку без specs/certificates.

Основные решения: [единое ТЗ](../../docs/ekt-assistant-spec.md). Общий workflow и финальные gates: [tasks/README.md](../README.md).

## План реализации

1. Exact normalized article искать relational index сначала; NOT_FOUND exact SKU отделить от похожих альтернатив.
2. Добавить lexical/category/typed attribute filters, затем vector fallback с query/token/top-k bounds.
3. Вернуть ProductDetails с specs/cert refs и immutable resultSetId/ordered product IDs/applied constraints; actual price hydration отдаёт CAT-03.
4. Реализовать server-side ordinal resolution/comparison по указанному resultSet и сохранённым filters из CHAT-04.
5. Сравнить существующий HNSW с exact baseline при restrictive category/scope filters; подобрать параметры по recall/latency.
6. Описать search validation и max limit, обновить springdoc/sdk; invalid price range/filter даёт ProblemDetail.

## Область изменений

backend/…/domain/catalog/Search…; ai/catalog/; web/catalog/; src/test/…/catalog/

Пути с многоточием обозначают предлагаемые package/file locations, а не уже существующие файлы. Публичный контракт, env и номера миграций согласовать по ownership в README.

## Constraints

- Произвольный SQL/metadata filter от модели/клиента не исполняется.
- Search index price/stock не актуальный offer.
- Похожая электротехника не автоматически совместимый аналог.
- Соблюдать [AGENTS.md](../../AGENTS.md), [backend/AGENTS.md](../../backend/AGENTS.md) и [frontend/AGENTS.md](../../frontend/AGENTS.md) для изменяемой области.

## Done when

- [ ] Exact SKU не вызывает embedding; отсутствующий exact SKU не превращается в карточку другого товара.
- [ ] Leading zeros и typo-name fixtures обработаны согласно схеме.
- [ ] «Первые два» адресует ранее показанные ID даже после изменения ранжирования.
- [ ] Ограниченный search работает с real/fake embeddings, результаты и ошибки имеют generated DTO.
- [ ] Проверки выполнены на своей ветке; PR содержит результат проверок и известные ограничения.

## Проверка и передача

При backend-изменениях: `cd backend && ./gradlew build`; при frontend: `cd frontend && npm run build && npm run lint`. API изменился → поднять backend и выполнить `cd frontend && npm run gen`. Помимо сборки воспроизвести перечисленные выше позитивные и негативные сценарии; mocks портов не считаются финальной продуктовой приёмкой.

Проверку нового clean/upgrade DB проводить в отдельном Compose project/volume. Общую demo-БД не сбрасывать. Результаты бизнес-сценариев после интеграции входят в QA-01/QA-02; нагрузка — PERF-02.

**Передать:** D1: search_catalog/compare port с результатами и references; D3: карточки/сравнение.

**Evidence после выполнения:** заполнить commit/PR, команды, ссылки на отчёты и фактический результат; до выполнения статус остаётся todo.

## Evidence — 23.09.2026, CAT services integration

Реализация передана в `codex/d2-catalog-services`; описание API и границ: [catalog handoff](../../docs/catalog/services-handoff.md). Реальные D1 ports подключены без изменений кода корзины.

Проверено: `DOCKER_HOST=unix:///Users/zubanyszarylkasynov/.docker/run/docker.sock TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock ./gradlew build --no-daemon` — **PASS**, включая существующие D1 regression tests и новые PostgreSQL/Redis Testcontainers. В catalog integration suite проверены exact/no embedding, missing SKU, typo/hard constraints, 12→7 и изменение цены без сброса при чтении, неизвестный/закрытый склад, дробный шаг, реальный SQL timeout, отказ embedding, 12+8/full20, отсутствие cart writes, ownership и стабильность ordinal snapshots.

Следующие gates остаются интегратору: объединить SQL draft в общую следующую миграцию, регенерировать SDK и прогнать итоговый HTTP/Compose QA-02. Синтетические embeddings не подтверждают real-model recall/latency; реальный partner stock API и partner compatibility rules не предоставлены и не проверены. До этих gates статус остаётся `in_progress`.
