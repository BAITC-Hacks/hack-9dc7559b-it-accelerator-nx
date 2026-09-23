---
id: ATT-03
owner: D2
status: todo
wave: 2
size: L
depends_on: ["ATT-01", "DATA-01"]
---

# ATT-03 — PDF, OCR и распознавание товара на JPEG

**Исполнитель:** D2. **Этап:** G2. **Объём:** L. Все задачи обязательны для полного scope; отметка todo означает, что реализация не выполнена.
**Зависимости для старта:** [ATT-01](../06-attachments/ATT-01-upload-and-jobs.md), [DATA-01](../00-foundation/DATA-01-sample-data-contract.md)
**Покрытие:** FR-6; AC-11/15.

## Goal

Поддержать текстовые и сканированные PDF, маркировку и визуальные кандидаты с фотографии.

## Context

OCR читает текст, но фото без текста требует отдельного visual recognition пути.

Основные решения: [единое ТЗ](../../docs/ekt-assistant-spec.md). Общий workflow и финальные gates: [tasks/README.md](../README.md).

## План реализации

1. PDFBox извлекает текст/таблицы; страницы без usable text render→OCR с page/pixel/time bounds.
2. JPEG orientation/preprocessing, OCR маркировки и bounding-box/page provenance; учитывать 0/O,1/I и низкое качество.
3. Реализовать VisionGateway для фото без usable text: реальный vision-capable adapter после compatibility check с D1 и deterministic test adapter.
4. Visual result содержит category/visible markings/observed attributes/candidates/quality flags; не утверждать скрытые номиналы по корпусу.
5. OCR runtime/language data упаковать в контейнер или отдельный service по контракту OPS; env/limits синхронизировать.
6. Fixtures: textPDF, scanPDF, mixedPDF, rotatedJPEG, blurredJPEG, product-onlyJPEG, huge pixel input. Live quality оценить отдельно от scripted mock.

## Область изменений

backend/…/ai/attachments/pdf/; ai/attachments/vision/; backend/Dockerfile; compose OCR configuration с D3; data/attachments/; src/test/…/vision/

Пути с многоточием обозначают предлагаемые package/file locations, а не уже существующие файлы. Публичный контракт, env и номера миграций согласовать по ownership в README.

## Constraints

- Не отдавать полный приватный файл LLM, когда хватает минимального crop/text; платёжные данные не продуктовая функция.
- Mock vision не доказывает распознавание реальной фотографии.
- Unknown attributes не превращаются в VERIFIED compatibility.
- Соблюдать [AGENTS.md](../../AGENTS.md), [backend/AGENTS.md](../../backend/AGENTS.md) и [frontend/AGENTS.md](../../frontend/AGENTS.md) для изменяемой области.

## Done when

- [ ] Каждый PDF/JPEG scenario даёт matched candidates либо честный needs_review с evidence.
- [ ] Фото без текста обрабатывается vision, а не только пустым OCR результатом.
- [ ] Huge/corrupt inputs ограничены, parser failure не блокирует chat API.
- [ ] Live smoke и container-only run проверяют OCR/vision отдельно; side effects корзины отсутствуют.
- [ ] Проверки выполнены на своей ветке; PR содержит результат проверок и известные ограничения.

## Проверка и передача

При backend-изменениях: `cd backend && ./gradlew build`; при frontend: `cd frontend && npm run build && npm run lint`. API изменился → поднять backend и выполнить `cd frontend && npm run gen`. Помимо сборки воспроизвести перечисленные выше позитивные и негативные сценарии; mocks портов не считаются финальной продуктовой приёмкой.

Проверку нового clean/upgrade DB проводить в отдельном Compose project/volume. Общую demo-БД не сбрасывать. Результаты бизнес-сценариев после интеграции входят в QA-01/QA-02; нагрузка — PERF-02.

**Передать:** ATT-04: visual/extraction candidates с uncertainty; D3: page/region/errors; OPS-02: полный runtime image.

**Evidence после выполнения:** заполнить commit/PR, команды, ссылки на отчёты и фактический результат; до выполнения статус остаётся todo.
