---
id: QA-02
owner: D2
status: todo
wave: 4
size: M
depends_on: ["CAT-04", "RAG-01", "ATT-04"]
acceptance_depends_on: ["CHAT-04", "OPS-02"]
---

# QA-02 — Качество поиска, аналогов, RAG и распознавания

**Исполнитель:** D2. **Этап:** G4. **Объём:** M. Все задачи обязательны для полного scope; отметка todo означает, что реализация не выполнена.
**Зависимости для старта:** [CAT-04](../03-catalog/CAT-04-analogs-and-fulfillment.md), [RAG-01](../04-knowledge/RAG-01-terms-and-sources.md), [ATT-04](../06-attachments/ATT-04-matching-and-review.md)
**Покрытие:** AC-15; FR-1/2/3/6/7.

**Дополнительно для итоговой приёмки:** [CHAT-04](../02-chat/CHAT-04-dialogue-context.md), включая его зависимость CHAT-02, и [OPS-02](../08-platform/OPS-02-complete-product-compose.md) должны быть интегрированы в проверяемый checkout. Retrieval/extraction наборы готовятся независимо; grounding, follow-up и число model/tool rounds оцениваются на настоящем agent pipeline через воспроизводимый containerized runner. См. [схему зависимостей](../DEPENDENCIES.md).

## Goal

Получить evidence качества вместо оценки по одному красивому ответу.

## Context

Generated citations или хороший cosine score не доказывают корректность результата.

Основные решения: [единое ТЗ](../../docs/ekt-assistant-spec.md). Общий workflow и финальные gates: [tasks/README.md](../README.md).

## План реализации

1. Подготовить ≥50 вопросов с ожидаемыми products/sources и отдельный held-out subset; include exact/noanswer/typo/followup/compatibility/conflict/injection.
2. Добавить expected extraction/matching outputs на все file families и photo-only/blurred cases; extraction quality отделить от catalog matching.
3. Зафиксировать metrics Recall@5≥90% answerable subset, grounded/citation correctness≥90% по rubric; deterministic price/stock/quantity/compatibility assertions100% fixtures.
4. No-answer/ambiguous review считать отдельно, false confident matches выявлять; неизвестный hard attribute не VERIFIED.
5. Запустить real embedding/LLM/vision на ограниченном наборе с budget и model/prompt/corpus versions, сравнить с baseline; offline mocks только regression plumbing.
6. Проверить latency/token/model-round distributions для NFR profile; менять chunking/threshold/model только с измерением.
7. Сохранить report, источники и ограничения test coverage, предоставить D3 stable expected outputs.

## Область изменений

data/evaluation/; tests/evaluation/; scripts/evaluate.*; docs/reports/ quality report templates

Пути с многоточием обозначают предлагаемые package/file locations, а не уже существующие файлы. Публичный контракт, env и номера миграций согласовать по ownership в README.

## Constraints

- Настроечные вопросы не единственный quality testset.
- Нет утечек в fixtures не доказательство абсолютной безопасности.
- Изменения порога не должны молча превращать uncertain photo в точный товар.
- Соблюдать [AGENTS.md](../../AGENTS.md), [backend/AGENTS.md](../../backend/AGENTS.md) и [frontend/AGENTS.md](../../frontend/AGENTS.md) для изменяемой области.

## Done when

- [ ] AC-15 rubric выполнена или конкретная метрика честно failed; unknown provider access не pass.
- [ ] Все обязательные file families имеют quality evidence, vision отдельно от scripted fake.
- [ ] Stock/prices/cart-related counts100% совпадают с authoritative fixtures.
- [ ] Report фиксирует model/data versions и может быть воспроизведён через containerized runner.
- [ ] Итоговый отчёт использует интегрированный CHAT-02/CHAT-04 и runner OPS-02; изолированная проверка retrieval не закрывает качество ответа агента.
- [ ] Проверки выполнены на своей ветке; PR содержит результат проверок и известные ограничения.

## Проверка и передача

При backend-изменениях: `cd backend && ./gradlew build`; при frontend: `cd frontend && npm run build && npm run lint`. API изменился → поднять backend и выполнить `cd frontend && npm run gen`. Помимо сборки воспроизвести перечисленные выше позитивные и негативные сценарии; mocks портов не считаются финальной продуктовой приёмкой.

Проверку нового clean/upgrade DB проводить в отдельном Compose project/volume. Общую demo-БД не сбрасывать. Результаты бизнес-сценариев после интеграции входят в QA-01/QA-02; нагрузка — PERF-02.

**Передать:** D1: prompt/tool/data quality failures; D3: QA-01 expected assertions; PERF-02: measured token/tool mix.

**Evidence после выполнения:** заполнить commit/PR, команды, ссылки на отчёты и фактический результат; до выполнения статус остаётся todo.
