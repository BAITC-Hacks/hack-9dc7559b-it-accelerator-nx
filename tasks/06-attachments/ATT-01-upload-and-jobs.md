---
id: ATT-01
owner: D2
status: todo
wave: 1
size: M
depends_on: ["FOUND-01"]
acceptance_depends_on: ["AUTH-01"]
---

# ATT-01 — Приватные вложения, storage и durable processing

**Исполнитель:** D2. **Этап:** G1. **Объём:** M. Все задачи обязательны для полного scope; отметка todo означает, что реализация не выполнена.
**Зависимости для старта:** [FOUND-01](../00-foundation/FOUND-01-contracts-and-ports.md)
**Покрытие:** FR-6; AC-11/13/14.

**Дополнительно для итоговой приёмки:** подключить [AUTH-01](../01-identity/AUTH-01-session-and-ownership.md) и проверить приватность вложений HTTP-тестами двух пользователей. Реализацию на TrustedScope fakes можно начать после FOUND-01; они не закрывают проверку ownership.

## Goal

Принимать файлы чата и обрабатывать их в ограниченной фоновой очереди.

## Context

Файл принадлежит conversation/visitor, не общей базе RAG; Office/PDF/JPEG входят в конечный scope.

Основные решения: [единое ТЗ](../../docs/ekt-assistant-spec.md). Общий workflow и финальные gates: [tasks/README.md](../README.md).

## План реализации

1. Использовать schema из общего gate: attachment/version/job/rows/reviews, immutable original storage key/hash, owner и desired version/tombstone.
2. Upload проверяет conversation access, signature/MIME/extension/size; checksum dedup scoped, path/key генерирует сервер.
3. Atomic metadata/job commit и orphan-object cleanup; API202 быстро возвращает IDs, provider/parser I/O в worker.
4. Stages validation/extraction/OCR/matching, bounded concurrency/deadline/memory, claim lease/fencing и CAS publish.
5. Status/source/delete защищены текущим ACL; deletion revoke-first и идемпотентная cleanup.
6. Зафиксировать allowlist xls/xlsx/doc/docx/pdf/jpg/jpeg и capability/limit metadata для UI; согласовать volume/OCR runtime с D3.

## Область изменений

backend/…/domain/attachments/; web/attachments/; ai/attachments/; db/migration/; config/; src/test/…/attachments/

Пути с многоточием обозначают предлагаемые package/file locations, а не уже существующие файлы. Публичный контракт, env и номера миграций согласовать по ownership в README.

## Constraints

- Нельзя исполнить macro, external reference, shell или remote URL из файла.
- Late worker не восстанавливает удалённый файл или старую версию.
- Сырой документ/распознанный личный текст не писать в application logs.
- Соблюдать [AGENTS.md](../../AGENTS.md), [backend/AGENTS.md](../../backend/AGENTS.md) и [frontend/AGENTS.md](../../frontend/AGENTS.md) для изменяемой области.

## Done when

- [ ] Все поддержанные семейства принимаются валидатором, остальные дают415, oversize413.
- [ ] Два visitors не читают чужой attachment/job/source; checksum не раскрывает наличие чужих файлов.
- [ ] Restart/retry не дублирует rows, delete-during-processing не публикует результат.
- [ ] Не требуются host parser binaries: путь storage/worker описан для Compose.
- [ ] Проверки выполнены на своей ветке; PR содержит результат проверок и известные ограничения.

## Проверка и передача

При backend-изменениях: `cd backend && ./gradlew build`; при frontend: `cd frontend && npm run build && npm run lint`. API изменился → поднять backend и выполнить `cd frontend && npm run gen`. Помимо сборки воспроизвести перечисленные выше позитивные и негативные сценарии; mocks портов не считаются финальной продуктовой приёмкой.

Проверку нового clean/upgrade DB проводить в отдельном Compose project/volume. Общую demo-БД не сбрасывать. Результаты бизнес-сценариев после интеграции входят в QA-01/QA-02; нагрузка — PERF-02.

**Передать:** D1: AttachmentPort и статусы; D3: upload/progress/review contract; ATT-02/03: safe extraction job context.

**Evidence после выполнения:** заполнить commit/PR, команды, ссылки на отчёты и фактический результат; до выполнения статус остаётся todo.
