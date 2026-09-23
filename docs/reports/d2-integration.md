# D2 — интеграция и QA-02, 23.09.2026

Код CAT-02…04, RAG-01, ATT-01…04 и QA-02 объединён в `codex/d2-remaining-services`.
DATA-01/CAT-01 теперь интегрированы с D1 JWT/ports. Конфликт main устранён
коммитом `8b327ed`, уже опубликованным в `origin/main`. Код корзины не менялся.

## Проверки

- Backend: полный `./gradlew build --no-daemon` — 125 тестов, 0 failures/skips; PostgreSQL/Redis Testcontainers,
  обе истории V3 → V4 → V5, реальные Office/PDF/JPEG fixtures.
- Frontend: `npm run build`, `npm test` (23 PASS), `npm run lint` (0 errors,
  два предупреждения ref cleanup в ChatPage/AttachmentPanel, пришедшие из main), `npm run gen:check` PASS.
- DATA validator: 36 товаров, 4 документа, 13 файлов, 54 вопроса — PASS.
- `docker compose -p hackalem-quality -f scripts/d2-quality-compose.yml ...`:
  отдельный чистый volume, Postgres/Redis/backend healthy; реальные D2 ports,
  OCR Tesseract eng+rus и полный HTTP agent pipeline. Общая demo-БД не сбрасывалась.
- OpenAPI экспортирован из работающего backend, SDK перегенерирован. Отдельный
  schema-test предотвращает коллизию AttachmentSelection с D1 cart Selection.

## Измеренные результаты

| Метрика | Offline baseline | Реальная модель (12 кейсов) |
|---|---:|---:|
| Завершённые agent runs | 54/54 | 10/12 |
| Product Recall@5 | 41/41 = 100% | 2/3 = 66,7% — FAILED |
| Recall ожидаемых RAG sources | 4/7 = 57,1% — FAILED | 0/3 — FAILED |
| Цены/остатки, фактически проверенные snapshots | 54/54 | 2/2 |
| Разрешение citations | 6/6 | нет выданных citations |
| Grounded answer rubric | scripted, не оценивается | 2/8 = 25% — FAILED |
| p50 / p95 latency | 229 / 448 мс | 1293 / 3863 мс |
| p50 / p95 tokens/run | scripted estimates | 536 / 1463 |
| p50 / p95 model rounds | scripted 2 / 2 | 1 / 2 |

`gpt-4o-mini-2024-07-18`, prompt `ekt-agent-v1`, embeddings
`text-embedding-3-small` (1536), corpus `synthetic-v1`, dataset `d2-eval-v2`.
Live embedding seed импортировал все 36 товаров. Контекстные/tool данные реальны;
scripted baseline явно отделён от live качества. Токены взяты из provider stream usage,
не выведены из количества tools. Выборка слишком мала для общей гарантии качества.

Все 13 attachment fixtures прошли ожидаемый жизненный цикл: валидные документы
→ NEEDS_REVIEW, corrupt/spoofed → отказ. DOC/DOCX/XLS/XLSX/text-PDF extraction
3/3 строки, включая ведущие нули и 2.5; для OCR label/rotated/scan полное совпадение
полей 1/3, mixed PDF 4/6. Неуверенные количества остаются неизвестными, требуется
ручная проверка. False confident matches = 0; автоматических selections нет.
12+8 и whole20, UNKNOWN/CLOSED, CAS review/reprocess, ownership, source revoke,
late worker fencing и отказ старой cart proposal после импорта проверены отдельно.

## Найденные проблемы и границы

1. Живая модель иногда не вызывает retrieval для условий, вызывает check_stock
   без result set или proposal с выдуманным ID. Сервер блокирует такие вызовы.
   Нужны дальнейшие изменения D1 routing/context/prompt, затем новый held-out run.
2. RAG full-token coverage даёт лишний no-answer для некоторых формулировок.
   Снижение порога на held-out данных не выполнялось; требуется отдельная настройка
   на tuning corpus и новая независимая приёмка.
3. Vision придумала текст на полностью размытом JPEG. Добавлен детерминированный
   blur gate: такой файл возвращает IMAGE_BLURRED_RETAKE до OCR/vision. Кроме того,
   model-only текст никогда не заполняет article/quantity/unit; визуальные
   наблюдения явно unverified. Исходное неудачное live evidence сохранено честно. Итоговый HTTP
   [blur regression](qa02-blur-regression.json) подтвердил NEEDS_REVIEW, пустые rows
   и IMAGE_BLURRED_RETAKE без provider calls.
4. Partner API/электротехнические правила поставщика не предоставлены; синтетические
   правила не сертифицируют реальные изделия. Parser deadline кооперативный между
   вызовами библиотек; жёсткая изоляция POI/PDFBox по CPU/RAM — отдельное усиление.
5. Настоящий conflict corpus/ACL проверен backend integration tests; конфликтные
   слова в пользовательском вопросе не считаются конфликтом двух источников.

**QA-02 выполнена как воспроизводимая оценка с конкретными failed metrics, как
допускает карточка. Качество продукта по AC-15 не принято; зелёная сборка не меняет
этот результат.** D2 реализации переданы; после исправлений RAG и blur guard выполнены отдельные
регрессии. Повторно принимать полное agent качество после исправлений D1.

## Артефакты и воспроизведение

- [Baseline JSON](qa02-offline.json), [live JSON + rubric](qa02-live.json).
- [Runner и методика](../../data/evaluation/README.md).
- [Catalog API](../catalog/services-handoff.md), [RAG API](../knowledge/rag-handoff.md),
  [Attachment API](../api/attachments-handoff.md).
- `python3 -m unittest discover -s tests/evaluation -v`
- `docker compose -p hackalem-quality -f scripts/d2-quality-compose.yml --profile evaluation run --build --rm evaluator`

Оригинальные V3 сохранены побайтно. V5 содержит D2 storage/projections и telemetry.
Файлы db/drafts используются изолированными тестами; runtime применяет только общую
V5. Shared demo containers не обновлялись этой веткой; опубликованное исправление
main и новые изменения D2 — разные коммиты/ветки.

## Последующие исправления и актуальный main

- [RAG HTTP regression](qa02-rag-regression.json): **7/7 FAQ** после нормализации
  общих слов запроса. Цифры, отрицания и характеристики сохраняются; порог не снижен.
  Эти уже наблюдавшиеся вопросы стали regression-набором: это не новый blind held-out
  результат. Исходная таблица выше относится к прогонам до исправлений.
- [Blur HTTP regression](qa02-blur-regression.json): NEEDS_REVIEW, rows=[],
  IMAGE_BLURRED_RETAKE; выдуманный model-only SKU/quantity больше не принимается.
- Ветка включает новый `origin/main` `610fc2b` с UI-03/UI-04. Исправлены две
  входящие ошибки TypeScript: отсутствующий type import и дубли default selection.
  Итог: **125 backend tests + 23 frontend tests PASS**, SDK drift check PASS.
- Новые SourcePage/AttachmentProvider пока работают с mock driver; D3 нужно
  подключить опубликованные generated endpoints и string revisions. Это не скрыто
  за fake backend и не выдано за готовую live frontend интеграцию.
- Карточки D2 закрыты по доставленным сервисам/проверкам. QA-02, согласно своей
  формулировке, допускает конкретную failed metric; **AC-15/release остаётся FAILED**.
