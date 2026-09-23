---
id: CAT-04
owner: D2
status: done
wave: 3
size: M
depends_on: ["CAT-02", "CAT-03"]
---

# CAT-04 — Совместимые аналоги и варианты частичной поставки

**Исполнитель:** D2. **Этап:** G3. **Объём:** M. Все задачи обязательны для полного scope; отметка todo означает, что реализация не выполнена.
**Зависимости для старта:** [CAT-02](../03-catalog/CAT-02-search-and-comparison.md), [CAT-03](../03-catalog/CAT-03-offers-and-stock.md)
**Покрытие:** FR-2; AC-2/6/9.

## Goal

Предлагать проверенные аналоги и точный состав 12+8 либо 20 alternative.

## Context

Vector similarity не доказывает electrical compatibility; часть товара может быть доступна.

Основные решения: [единое ТЗ](../../docs/ekt-assistant-spec.md). Общий workflow и финальные gates: [tasks/README.md](../README.md).

## План реализации

1. Создать category-specific versioned hard rules на данных партнёра или явно synthetic fixtures, включая обязательные specs/единицы.
2. Фильтровать несовместимые/unknown критические параметры до ranking; NEEDS_SPECIFICATION не считать VERIFIED.
3. Ранжировать оставшиеся кандидаты по мягким признакам/price/availability; объяснение из matchedRequirements/differences.
4. Вычислить immutable AlternativePlan: partial original + replacement deficit или full alternative; проверить каждую line и итоговую quantity.
5. Вернуть optionId/version/reasons/current offers; D1 создаёт отдельный proposal только после выбора пользователя.
6. При отсутствии реального совместимого товара честно вернуть no suitable analog, не выдумывать обязательный ответ.

## Область изменений

backend/…/domain/catalog/Analogs…; ai/catalog/; data/compatibility-rules/; src/test/…/analogs/

Пути с многоточием обозначают предлагаемые package/file locations, а не уже существующие файлы. Публичный контракт, env и номера миграций согласовать по ownership в README.

## Constraints

- LLM не сочиняет safety-critical compatibility rules.
- Все quantities соблюдают units/pack steps; количества не увеличивать ради кратности без согласия.
- CAT-04 никогда не вызывает CartPort mutation.
- Соблюдать [AGENTS.md](../../AGENTS.md), [backend/AGENTS.md](../../backend/AGENTS.md) и [frontend/AGENTS.md](../../frontend/AGENTS.md) для изменяемой области.

## Done when

- [ ] Stock0 fixture выдаёт ≥1 существующий совместимый аналог с explanation.
- [ ] Сходное имя при несовместимом номинале исключено; missing hard spec вызывает уточнение.
- [ ] Stock12/request20 даёт валидные варианты12+8 и full20 на предусмотренном dataset.
- [ ] Выбор варианта и повтор find_analogs не меняют корзину.
- [ ] Проверки выполнены на своей ветке; PR содержит результат проверок и известные ограничения.

## Проверка и передача

При backend-изменениях: `cd backend && ./gradlew build`; при frontend: `cd frontend && npm run build && npm run lint`. API изменился → поднять backend и выполнить `cd frontend && npm run gen`. Помимо сборки воспроизвести перечисленные выше позитивные и негативные сценарии; mocks портов не считаются финальной продуктовой приёмкой.

Проверку нового clean/upgrade DB проводить в отдельном Compose project/volume. Общую demo-БД не сбрасывать. Результаты бизнес-сценариев после интеграции входят в QA-01/QA-02; нагрузка — PERF-02.

**Передать:** D1: bounded find_analogs tool + optionId snapshots; D3: объяснения, различия и выбор комплектации.

**Evidence после выполнения:** заполнить commit/PR, команды, ссылки на отчёты и фактический результат; до выполнения статус остаётся todo.

## Evidence — 23.09.2026, CAT services integration

Реализация передана в `codex/d2-catalog-services`; описание API и границ: [catalog handoff](../../docs/catalog/services-handoff.md). Реальные D1 ports подключены без изменений кода корзины.

Проверено: `DOCKER_HOST=unix:///Users/zubanyszarylkasynov/.docker/run/docker.sock TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock ./gradlew build --no-daemon` — **PASS**, включая существующие D1 regression tests и новые PostgreSQL/Redis Testcontainers. В catalog integration suite проверены exact/no embedding, missing SKU, typo/hard constraints, 12→7 и изменение цены без сброса при чтении, неизвестный/закрытый склад, дробный шаг, реальный SQL timeout, отказ embedding, 12+8/full20, отсутствие cart writes, ownership и стабильность ordinal snapshots.

Следующие gates остаются интегратору: объединить SQL draft в общую следующую миграцию, регенерировать SDK и прогнать итоговый HTTP/Compose QA-02. Синтетические embeddings не подтверждают real-model recall/latency; реальный partner stock API и partner compatibility rules не предоставлены и не проверены. До этих gates статус остаётся `in_progress`.


## Итоговая интеграция D2

Объединено в `codex/d2-remaining-services`: V5, D1 JWT/ports, актуальный OpenAPI и generated SDK.
Полная backend/frontend сборка и отдельный Compose HTTP runner проверены.
[Итоговый отчёт и failed quality gates](../../docs/reports/d2-integration.md).
