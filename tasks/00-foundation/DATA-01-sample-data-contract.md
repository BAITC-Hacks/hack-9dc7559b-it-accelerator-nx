---
id: DATA-01
owner: D2
status: done
wave: 0
size: M
depends_on: []
---

# DATA-01 — Синтетические данные и ожидаемые сценарии

**Исполнитель:** D2. **Этап:** G0. **Объём:** M. Все задачи обязательны для полного scope; отметка todo означает, что реализация не выполнена.
**Зависимости для старта:** Нет — можно начать сразу.
**Покрытие:** FR-1/2/3/6/7; AC-1…11.

## Goal

Дать всем трём разработчикам стабильные реальные по структуре fixtures с первого этапа.

## Context

Партнёрские данные могут отсутствовать; существующая V2 не содержит достаточную количественную модель.

Основные решения: [единое ТЗ](../../docs/ekt-assistant-spec.md). Общий workflow и финальные gates: [tasks/README.md](../README.md).

## План реализации

1. Создать ≥30 товаров в ≥3 категориях, exact SKU с ведущими нулями, warehouse quantities, units/step/minimum, price/currency/specs и sample certificates. Все данные пометить synthetic.
2. Определить стабильные SKU для in-stock, zero-stock, stock12/request20, analog8, analog20, несовместимого похожего товара и not-found. Подготовить snapshots price/stock changes и timeout/unknown.
3. Добавить terms FAQ с источниками/version и ожидаемыми ответами; добавить образцы xls/xlsx/doc/docx/text+scan PDF/JPEG и expected rows. Можно сначала подготовить маленькие валидные fixtures, затем расширять набор в ATT задачах.
4. Добавить схемы данных/валидацию: article без потери нулей, BigDecimal quantities/prices, stock unknown отдельно от zero, единицы и ограничения категорий.
5. С D1 согласовать port DTO из FOUND-01; нормализовать fixtures под contract snapshot, не создавать frontend HTTP types.
6. Записать происхождение fixtures, ожидаемые AC steps и команды validator; передать их D1/D3 до завершения каталога.

## Область изменений

data/sample_catalog/; data/purchase_terms/; data/attachments/; data/expected/; scripts/validate-demo-data.sh

Пути с многоточием обозначают предлагаемые package/file locations, а не уже существующие файлы. Публичный контракт, env и номера миграций согласовать по ownership в README.

## Constraints

- Не использовать секреты и реальные персональные накладные.
- Не превращать старый stock boolean в количество 1.
- Fixture analog compatibility должна быть сформулирована явно; электротехнические правила не выводить из похожего имени.
- Соблюдать [AGENTS.md](../../AGENTS.md), [backend/AGENTS.md](../../backend/AGENTS.md) и [frontend/AGENTS.md](../../frontend/AGENTS.md) для изменяемой области.

## Done when

- [x] Validator проверяет обязательные поля, ссылки и уникальные SKU/IDs; invalid examples отделены от seed.
- [x] Каждый AC-1…10 имеет конкретный reproducible dataset; AC-11 имеет каждое семейство файлов.
- [x] Сценарий 12+8 и whole20 математически и по unit/stock корректен.
- [ ] D1/D3 используют одинаковые IDs и значения, а не создают несовместимые локальные моки.
- [ ] Проверки выполнены на своей ветке; PR содержит результат проверок и известные ограничения.

## Проверка и передача

При backend-изменениях: `cd backend && ./gradlew build`; при frontend: `cd frontend && npm run build && npm run lint`. API изменился → поднять backend и выполнить `cd frontend && npm run gen`. Помимо сборки воспроизвести перечисленные выше позитивные и негативные сценарии; mocks портов не считаются финальной продуктовой приёмкой.

Проверку нового clean/upgrade DB проводить в отдельном Compose project/volume. Общую demo-БД не сбрасывать. Результаты бизнес-сценариев после интеграции входят в QA-01/QA-02; нагрузка — PERF-02.

**Передать:** D1: stock/cart scenarios и quantity rules; D3: product/proposal/upload examples; D2: baseline качества и данные для idempotent initializer.

**Evidence после выполнения:** заполнить commit/PR, команды, ссылки на отчёты и фактический результат; до выполнения статус остаётся todo.

## Evidence DATA-01 — 23.09.2026

Подготовлено в `codex/d2-data-services`, только DATA-01. Commit/PR: изменения
подготовлены для пользовательского коммита; следующие задачи не включены.

- [Данные и контракт передачи](../../data/README.md): 36 товаров / 3 категории,
  4 versioned FAQ источника, 13 файлов (включая негативные), 54 исходных вопроса.
- [JSON Schema](../../data/sample_catalog/catalog.schema.json) и
  [validator](../../scripts/validate-demo-data.sh): PASS. Проверены десятичные строки,
  ведущие нули, уникальность, unknown/zero, несовместимый и неполный аналог,
  недоступный склад, 12+8/whole20, ссылки, SHA-256 и негативные примеры.
- XLS/XLSX, DOC/DOCX, text/scan/mixed PDF, label/rotated/blurred/product-only JPEG:
  файлы созданы, Office-конвертация прошла. XLS после обратной конвертации
  сохраняет `000001`, `000013`, `2.5`. DOCX/PDF просмотрены после рендера;
  PDF проверены по количеству страниц и наличию/отсутствию текстового слоя.
- `cd backend && ./gradlew build --no-daemon`: PASS (исходный backend, тестов пока нет).
- `cd frontend && npm run build && npm run lint`: PASS.
- API, миграции, runtime, корзина и generated SDK не изменены; БД не сбрасывалась.

**Оставшийся gate:** FOUND-01 отсутствует в исходном checkout. Формат fixtures
следует его требованиям (string IDs, decimal strings), но принятие контракта
и использование одинаковых fixtures разработчиками D1/D3 пока не подтверждено.
Поэтому статус остаётся `in_progress`; полная приёмка и пункт передачи не отмечены
как выполненные. Проверки данных не засчитываются за ATT, QA-02 или продуктовый E2E.


## Итоговая интеграция D2

Объединено в `codex/d2-remaining-services`: V5, D1 JWT/ports, актуальный OpenAPI и generated SDK.
Полная backend/frontend сборка и отдельный Compose HTTP runner проверены.
[Итоговый отчёт и failed quality gates](../../docs/reports/d2-integration.md).
