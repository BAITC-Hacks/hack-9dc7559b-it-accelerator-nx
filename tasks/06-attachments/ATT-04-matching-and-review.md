---
id: ATT-04
owner: D2
status: in_progress
wave: 3
size: M
depends_on: ["CAT-02", "ATT-02", "ATT-03"]
---

# ATT-04 — Сопоставление каталогу и ручная проверка строк

**Исполнитель:** D2. **Этап:** G3. **Объём:** M. Все задачи обязательны для полного scope; отметка todo означает, что реализация не выполнена.
**Зависимости для старта:** [CAT-02](../03-catalog/CAT-02-search-and-comparison.md), [ATT-02](../06-attachments/ATT-02-office-extraction.md), [ATT-03](../06-attachments/ATT-03-pdf-ocr-and-photo.md)
**Покрытие:** FR-6; AC-11/13.

## Goal

Превратить распознавание в контролируемый пользователем набор товаров для предложения.

## Context

Неоднозначное распознавание SKU/quantity может привести к неверному заказу, поэтому review отдельный шаг.

Основные решения: [единое ТЗ](../../docs/ekt-assistant-spec.md). Общий workflow и финальные gates: [tasks/README.md](../README.md).

## План реализации

1. Проводить matching exact article→alias→lexical/vector/vision candidates с category/hard attribute checks.
2. Вернуть per-row matched/ambiguous/unmatched/needs_quantity, candidate evidence, quantity/unit и source location.
3. Не объявлять cosine/LLM self-confidence вероятностью; пороги калибровать по fixtures и маркировать сомнения.
4. POST review проверяет expectedVersion, owner, выбранный реальный product ID и units/quantity; stale revision409.
5. User corrections сохраняются отдельно от extracted data и не затираются поздним OCR.
6. AttachmentPort отдаёт только явно selected/reviewed строки; затем D1 создаёт proposal и запрашивает отдельное cart confirmation.

## Область изменений

backend/…/domain/attachments/Matching…; ai/attachments/; web/attachments/; src/test/…/matching/

Пути с многоточием обозначают предлагаемые package/file locations, а не уже существующие файлы. Публичный контракт, env и номера миграций согласовать по ownership в README.

## Constraints

- Review строк не согласие на добавление.
- Quantity неизвестна или hard specs conflict — строка не проходит автоматически.
- Чужие candidate/source IDs проверяются сервером.
- Соблюдать [AGENTS.md](../../AGENTS.md), [backend/AGENTS.md](../../backend/AGENTS.md) и [frontend/AGENTS.md](../../frontend/AGENTS.md) для изменяемой области.

## Done when

- [ ] Exact/conflicting/ambiguous/unmatched fixtures показывают правильный статус и требуют нужных уточнений.
- [x] Смена количества/выбора пересчитывает версию; два stale reviewers не затирают решение.
- [ ] Неподтверждённая строка не появляется в proposal; подтверждённая остаётся точной по quantity/unit.
- [x] После reload/reprocess user review сохранён, private scope не потерян.
- [x] Проверки выполнены на своей ветке; PR содержит результат проверок и известные ограничения.

## Проверка и передача

При backend-изменениях: `cd backend && ./gradlew build`; при frontend: `cd frontend && npm run build && npm run lint`. API изменился → поднять backend и выполнить `cd frontend && npm run gen`. Помимо сборки воспроизвести перечисленные выше позитивные и негативные сценарии; mocks портов не считаются финальной продуктовой приёмкой.

Проверку нового clean/upgrade DB проводить в отдельном Compose project/volume. Общую demo-БД не сбрасывать. Результаты бизнес-сценариев после интеграции входят в QA-01/QA-02; нагрузка — PERF-02.

**Передать:** D1: ReviewedItems versioned contract; D3: final review flow и выбор в proposal; QA-02: matching dataset.

**Evidence после выполнения:** заполнить commit/PR, команды, ссылки на отчёты и фактический результат; до выполнения статус остаётся todo.


## Evidence реализации D2 — 2026-09-23

Код: `49ad17e` и follow-up на ветке `codex/d2-attachment-services`. Handoff: [API, лимиты и оставшиеся gates](../../docs/api/attachments-handoff.md). Фактически выполнено: focused Gradle suite `--tests 'com.hackalem.attachments.*'` (22 tests, PostgreSQL/Redis Testcontainers, реальные D1 JWT двух visitors, реальные XLS/XLSX/DOC/DOCX/PDF/JPEG fixtures, локальный Tesseract). Общую demo-БД тесты не изменяют. Зависимости POI/PDFBox при изолированной проверке подставлены init script; основной build/config принадлежит интеграционному коммиту root. PR не создавался: пользователь запросил commit.

Reprocess API защищён ACL + `expectedVersion`; новая desired version отзывает старые claims. Review и исходное evidence сохраняются отдельно; изменившийся OCR не перепривязывает выбранный товар по номеру строки. OCR возвращает word boxes в координатах исходного JPEG или 144-DPI PDF page, с ограниченным размером provenance.

Статус остаётся `in_progress` до общей интеграционной проверки образа/SDK и применимых внешних gates: container-only OCR, live vision quality. Проверка scripted vision подтверждает маршрутизацию и uncertainty, не качество реальной модели. Жёсткая изоляция POI/PDFBox в отдельном процессе не реализована: есть лимиты input/ZIP/pages/rows/text/pixels и cooperative deadline, а зависшая операция библиотеки требует отдельного worker container/process для жёсткого прерывания. Этот предел явно отражён в handoff, а не выдан за пройденный gate.
