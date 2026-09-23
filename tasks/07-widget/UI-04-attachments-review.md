---
id: UI-04
owner: D3
status: todo
wave: 3
size: M
depends_on: ["UI-02"]
---

# UI-04 — Загрузка файлов и проверка распознанных позиций

**Исполнитель:** D3. **Этап:** G3. **Объём:** M. Все задачи обязательны для полного scope; отметка todo означает, что реализация не выполнена.
**Зависимости для старта:** [UI-02](../07-widget/UI-02-chat-and-streaming.md)
**Покрытие:** FR-6; AC-11.

## Goal

Провести пользователя от Excel/Word/PDF/JPEG до проверенного выбора товаров.

## Context

Фоновые OCR/matching jobs могут быть долгими/неоднозначными, partial results надо показывать честно.

Основные решения: [единое ТЗ](../../docs/ekt-assistant-spec.md). Общий workflow и финальные gates: [tasks/README.md](../README.md).

## План реализации

1. Upload control с server capability allowlist/limits и progress; принять xls/xlsx/doc/docx/pdf/jpg/jpeg.
2. Показать stage queued/extracting/OCR/matching/ready/review/failed и ясную ошибку; polling только active jobs с backoff/visibility handling.
3. Рендерить rows с raw text, source location, candidate products, quantity/unit, confidence category и warnings.
4. Позволить исправить quantity/unit, выбрать кандидата/исключить строку; review mutation содержит expectedVersion, конфликт обновляет экран.
5. Кнопка продолжения создаёт только выбор для proposal; дальнейшее cart confirmation — UI-03, никогда auto-add из файла.
6. Reload и повтор upload не теряют review/не дублируют job; source открывается с текущим auth.
7. Подключить настоящие ATT-01…04 на integration gate, пройти все форматы.

## Область изменений

frontend/src/components/attachments/; hooks/; components/chat/; browser tests/

Пути с многоточием обозначают предлагаемые package/file locations, а не уже существующие файлы. Публичный контракт, env и номера миграций согласовать по ownership в README.

## Constraints

- accept атрибут не server validation.
- Не скрывать unmatched/ambiguous строки и не превращать unknown quantity в1.
- Review не равно согласию на корзину.
- Соблюдать [AGENTS.md](../../AGENTS.md), [backend/AGENTS.md](../../backend/AGENTS.md) и [frontend/AGENTS.md](../../frontend/AGENTS.md) для изменяемой области.

## Done when

- [ ] Каждый формат проходит upload→progress→review→proposal flow в UI.
- [ ] Corrupt/oversize/unsupported files дают осмысленную ошибку.
- [ ] Изменённая review version не перезаписывается stale вкладкой; чужой source не открывается.
- [ ] До отдельного confirm ни один файл не меняет cart.
- [ ] Проверки выполнены на своей ветке; PR содержит результат проверок и известные ограничения.

## Проверка и передача

При backend-изменениях: `cd backend && ./gradlew build`; при frontend: `cd frontend && npm run build && npm run lint`. API изменился → поднять backend и выполнить `cd frontend && npm run gen`. Помимо сборки воспроизвести перечисленные выше позитивные и негативные сценарии; mocks портов не считаются финальной продуктовой приёмкой.

Проверку нового clean/upgrade DB проводить в отдельном Compose project/volume. Общую demo-БД не сбрасывать. Результаты бизнес-сценариев после интеграции входят в QA-01/QA-02; нагрузка — PERF-02.

**Передать:** D2: parser/matching UX feedback; QA-01: file-family browser scenarios.

**Evidence после выполнения:** заполнить commit/PR, команды, ссылки на отчёты и фактический результат; до выполнения статус остаётся todo.
