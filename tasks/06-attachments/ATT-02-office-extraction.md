---
id: ATT-02
owner: D2
status: todo
wave: 2
size: M
depends_on: ["ATT-01", "DATA-01"]
---

# ATT-02 — Excel и Word: строки, количества и координаты

**Исполнитель:** D2. **Этап:** G2. **Объём:** M. Все задачи обязательны для полного scope; отметка todo означает, что реализация не выполнена.
**Зависимости для старта:** [ATT-01](../06-attachments/ATT-01-upload-and-jobs.md), [DATA-01](../00-foundation/DATA-01-sample-data-contract.md)
**Покрытие:** FR-6; AC-11.

## Goal

Извлекать проверяемые строки спецификации из Excel и Word.

## Context

Входные документы содержат артикулы, количество, цену и итоги рядом; ошибки разбора не должны менять заказ.

Основные решения: [единое ТЗ](../../docs/ekt-assistant-spec.md). Общий workflow и финальные gates: [tasks/README.md](../README.md).

## План реализации

1. Подключить совместимые Apache POI HSSF/XSSF/HWPF/XWPF и bounded readers для xls/xlsx/doc/docx.
2. Вернуть единый ExtractionResult с raw text, SKU/quantity/unit candidates, warnings и source sheet/row/cell/paragraph/table.
3. Отделить headers/totals от products, сохранить leading zeros, decimal comma, units и порядок строк; неизвестное количество не default1.
4. Merged headers/multi-sheet/paragraph specs обрабатывать определённо; cached formulas только читать с пометкой, не вычислять внешние ссылки/macros.
5. Ограничить zip expansion/rows/string lengths/runtime; повреждённые/encrypted files возвращают конкретные errors.
6. Добавить expected JSON для каждого расширения и неоднозначных строк; UI review получает warnings, не ложную уверенность.

## Область изменений

backend/…/ai/attachments/office/; backend/build.gradle; data/attachments/office/; src/test/…/extraction/

Пути с многоточием обозначают предлагаемые package/file locations, а не уже существующие файлы. Публичный контракт, env и номера миграций согласовать по ownership в README.

## Constraints

- Не выполнять formulas/macros и network links.
- OCR/parser confidence не считается вероятностью корректности товара.
- Количество не брать из price/total column по умолчанию.
- Соблюдать [AGENTS.md](../../AGENTS.md), [backend/AGENTS.md](../../backend/AGENTS.md) и [frontend/AGENTS.md](../../frontend/AGENTS.md) для изменяемой области.

## Done when

- [ ] Валидные xls/xlsx/doc/docx fixtures дают ожидаемые rows и provenance.
- [ ] 00123 и fractional quantity сохраняются без округления/потери нулей.
- [ ] Corrupt/oversize/ambiguous document даёт failure либо review, не пустой success.
- [ ] Работает в backend image без установленного Office на host.
- [ ] Проверки выполнены на своей ветке; PR содержит результат проверок и известные ограничения.

## Проверка и передача

При backend-изменениях: `cd backend && ./gradlew build`; при frontend: `cd frontend && npm run build && npm run lint`. API изменился → поднять backend и выполнить `cd frontend && npm run gen`. Помимо сборки воспроизвести перечисленные выше позитивные и негативные сценарии; mocks портов не считаются финальной продуктовой приёмкой.

Проверку нового clean/upgrade DB проводить в отдельном Compose project/volume. Общую demo-БД не сбрасывать. Результаты бизнес-сценариев после интеграции входят в QA-01/QA-02; нагрузка — PERF-02.

**Передать:** ATT-04: normalised extraction rows; D3: row warnings/source coordinates; QA: four extension regression fixtures.

**Evidence после выполнения:** заполнить commit/PR, команды, ссылки на отчёты и фактический результат; до выполнения статус остаётся todo.
