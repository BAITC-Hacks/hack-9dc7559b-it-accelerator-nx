# RAG-01: versioned KB

Реальный `KnowledgeService` реализует D1 `domain.port.KnowledgePort` в профилях,
кроме `contract`/`test`. `AgentTools.search_purchase_terms` получает его через
существующий ObjectProvider, выдаёт `sources.result` и сохраняет выдержки в истории.
Для `d2` используется реальный corpus с offline LLM; этот режим не доказывает
качество живой модели.

## Runtime и API

- `GET /api/knowledge/search?query=…&characterBudget=6000` возвращает `answerability`,
  `chunks` с SHA-256, `sourceKey` (DATA fixture id), `synthetic`, `untrusted`, page/heading, version UUID и
  `citationAllowlist`. Все endpoints требуют проверенную visitor session.
- `GET /api/sources/{documentId}/versions/{versionId}` скачивает immutable plain
  text с повторной проверкой текущего ACL. Версия в `SourceRef.version` — UUID;
  человекочитаемый version label включён в title. Источник v1 открывается после v2;
  tombstone или смена PUBLIC→PRIVATE блокирует прежний доступ.
- ADMIN: `POST /api/admin/knowledge/documents` с `{externalId,title,version,
  visibility,sourceUrl,text,tags,synthetic,semanticIndex}` ставит durable job.
  `visibility` — `PUBLIC`/`PRIVATE`; owner берётся из verified principal.
- ADMIN: `GET /api/admin/knowledge/jobs/{id}`, `POST
  /api/admin/knowledge/documents/{id}/reindex`, `DELETE
  /api/admin/knowledge/documents/{id}`. Import/reindex отвечают 202 и job;
  worker публикует только полную версию. Job epoch передаётся строкой.

Пример чтения: login → bearer token → search «Доставка по Алматы?» →
`chunks[0].sourcePath`. Body не принимает ACL/owner. `sourceUrl` сохраняется
как метка происхождения; сервер никогда не скачивает указанный URL.

Root объединяет `db/drafts/knowledge.sql` с attachment schema в V5.
Существующие V1–V4 не изменяются. Настройки, которые root добавляет в
application/env/compose:

| Property | Env | Default |
| --- | --- | --- |
| `app.knowledge.seed-enabled` | `KNOWLEDGE_SEED_ENABLED` | `true` |
| `app.knowledge.seed-location` | `KNOWLEDGE_SEED_LOCATION` | `file:../data/purchase_terms/terms.json` |
| `app.knowledge.worker-enabled` | `KNOWLEDGE_WORKER_ENABLED` | `true` |

В compose seed-location указывает на readonly `/app/data/purchase_terms/terms.json`.
Seed создаёт только отсутствующие PUBLIC synthetic документы; рестарт не
восстанавливает tombstone и не перезаписывает admin изменения.

## Жизненный цикл и границы

Original хранится один раз в `document_versions`, object key указывает на
immutable DB row; SHA-256 считается по UTF-8. Новая версия заменяет desired,
active остаётся прежней до атомарной ready публикации. Reindex проверяет
прочитанную active/desired версию под row lock; параллельный delete или новый
import даёт 409 `knowledge_reindex_stale`, не воскрешает удалённый документ. Lease epoch, текущая
lease и CAS desired/tombstone защищают публикацию от позднего worker. Failed
reindex сохраняет старую ready версию. Chunks/old originals сохраняются для
старых citations; неиспользуемые vectors освобождаются при reindex/delete.
Expired jobs можно перехватить до трёх попыток, затем job получает FAILED.

Parser поддерживает plain text, Markdown headings и form-feed страницы;
chunk максимум 2400 символов, overlap 240, source максимум 200000 символов.
PDF/Office parsing выполняет attachment pipeline отдельно. Private uploads
в общую KB не попадают. Provider I/O вынесен из DB transactions и scheduler
thread; один bounded worker на instance.

Baseline lexical-v1 требует покрытие значимых токенов запроса. Контекст
ограничен characterBudget; целые chunks не обрезаются посреди отрицания.
Embedding opt-in использует существующий CatalogEmbeddingProvider, отдельную
chunk table vector(1536), versioned vectorSpace и дополнительное ранжирование.
Fake/live vectors не смешиваются; fake режим не является semantic quality
проходом. Смена provider space требует reindex; provider failure при retrieval
оставляет явно помеченный lexical fallback.

Ответы о цене/остатке товара направляются в каталог. Неизвестные факты дают
NO_ANSWER; различающиеся доступные документы одной темы дают CONFLICT без
попытки придумать приоритет. D1 list port возвращает [] при no-answer,
409 `knowledge_conflict` при конфликте и 503 `knowledge_source_unavailable`
при недоступности. Подробный endpoint дополнительно показывает статусы.

Известные document-instruction patterns исключаются из retrieval и embedding;
это эвристическая защита, не универсальный детектор prompt injection. Все
source text остаются untrusted, D1 system policy и server tool/confirm ACL
сохраняют независимую защиту. KB не исполняет команды и не меняет корзину.

## Проверки и ограничения

`./gradlew test --tests 'com.hackalem.knowledge.*' --no-daemon` — отдельный
PostgreSQL pgvector Testcontainer, реальные JWT/HTTP/admin/owner checks:
FAQ/no-answer, budget, citation allowlist, private leakage denial, immutable
v1→v2, ACL revoke, late job/delete, stale reindex prepare, lease fencing, failed reindex, injection
без provider/cart side effects, conflicts, idempotent seed и chunk coordinates.
После audit исправлений PASS 15 RAG tests. Отдельный тест читает все cases из
`data/expected/terms-answers.json`: `sourceId` сопоставляется с `chunk.sourceKey`,
`sourceVersion` с `chunk.versionLabel`; `documentId` и `versionId` остаются
серверными UUID. Проверяются expected facts и скачивание того же immutable text.

`./gradlew build --no-daemon` — PASS: 81 tests, 0 failures (в том числе 12 RAG).
Объединённый D1 tool/API smoke проверяется при интеграции.
Живые embeddings/LLM, groundedness evaluation QA-02, партнёрские документы,
high-load retrieval и внешнее развёртывание отдельно не подтверждены. Lexical
scan ограничен 2000 chunks; это bounded demo baseline, не ANN production index.
Нет произвольного LLM-синтеза ответа в сервисе: он возвращает проверяемые цитаты.
