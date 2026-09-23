---
id: RAG-01
owner: D2
status: in_progress
wave: 2
size: L
depends_on: ["CAT-01"]
---

# RAG-01 — Условия покупки, versioned RAG и источники

**Исполнитель:** D2. **Этап:** G2. **Объём:** L. Все задачи обязательны для полного scope; отметка todo означает, что реализация не выполнена.
**Зависимости для старта:** [CAT-01](../03-catalog/CAT-01-import-and-schema.md)
**Покрытие:** FR-3; AC-3/13/15.

## Goal

Отвечать по KB партнёра с проверяемым источником, сохранив приватность вложений.

## Context

Текущая vector table предназначена продуктам; нужен отдельный versioned корпус условий/сертификатов.

Основные решения: [единое ТЗ](../../docs/ekt-assistant-spec.md). Общий workflow и финальные gates: [tasks/README.md](../README.md).

## План реализации

1. Добавить documents/versions/chunks/citations/jobs из registry; immutable source object key/hash у версии, desired/active version и tombstone.
2. Admin ingestion: parse headings/pages → bounded chunks → embedding → CAS publish только полной ready версии; schema ведёт Flyway/JdbcTemplate adapter.
3. Retrieve active allowed scope только server ACL; metadata клиента не может переопределить scope.
4. Возвращать source labels/version/location и answerability; ContextBuilder budget/history работает через D1 KnowledgePort.
5. Защитить certificate/KB source route актуальным ACL; old citation v1 после publish v2 открывает v1, revoked source недоступен.
6. Сделать no-answer/conflict/injection fixtures и delete/reindex cleanup; private attachments не индексировать в shared KB автоматически.

## Область изменений

backend/…/domain/documents/; ai/rag/; web/sources/; db/migration/; data/purchase_terms/; src/test/…/rag/

Пути с многоточием обозначают предлагаемые package/file locations, а не уже существующие файлы. Публичный контракт, env и номера миграций согласовать по ownership в README.

## Constraints

- KB цены/остатки не заменяют CAT-03.
- Сырой документ — данные, не system instructions.
- Изменение embedding dimensions/model требует new version/index, не смешивание векторов.
- Соблюдать [AGENTS.md](../../AGENTS.md), [backend/AGENTS.md](../../backend/AGENTS.md) и [frontend/AGENTS.md](../../frontend/AGENTS.md) для изменяемой области.

## Done when

- [ ] FAQ payment/delivery/minimum отвечает по fixture source; absent fact даёт честный no-answer.
- [ ] Несанкционированные chunks не попадут в prompt, cache или source endpoint.
- [ ] Late job не публикует старую/удалённую версию; reindex не теряет предыдущую ready.
- [ ] Citation ID существует в retrieval allowlist, а groundedness отдельно проверяется QA-02.
- [ ] Проверки выполнены на своей ветке; PR содержит результат проверок и известные ограничения.

## Проверка и передача

При backend-изменениях: `cd backend && ./gradlew build`; при frontend: `cd frontend && npm run build && npm run lint`. API изменился → поднять backend и выполнить `cd frontend && npm run gen`. Помимо сборки воспроизвести перечисленные выше позитивные и негативные сценарии; mocks портов не считаются финальной продуктовой приёмкой.

Проверку нового clean/upgrade DB проводить в отдельном Compose project/volume. Общую demo-БД не сбрасывать. Результаты бизнес-сценариев после интеграции входят в QA-01/QA-02; нагрузка — PERF-02.

**Передать:** D1: search_purchase_terms и sources; D3: versioned citation UI; OPS-02: KB seed+ready.

**Evidence после выполнения:** заполнить commit/PR, команды, ссылки на отчёты и фактический результат; до выполнения статус остаётся todo.


## Evidence D2 (2026-09-23)

Реализованы versioned ingestion/lease-CAS, shared/private ACL, immutable source
route, D1 KnowledgePort, bounded lexical retrieval и opt-in versioned embeddings.
Локальные PostgreSQL/JWT checks: `./gradlew test --tests 'com.hackalem.knowledge.*' --no-daemon`
(12 tests). Полный `./gradlew build --no-daemon`: PASS, 81 tests/0 failures. Описание API, настройки и ограничения: [handoff](../../docs/knowledge/rag-handoff.md).
Статус остаётся in_progress до объединённой V5/SDK/QA-02 проверки; реальная LLM
groundedness и deployed E2E не утверждаются на основании offline тестов.
