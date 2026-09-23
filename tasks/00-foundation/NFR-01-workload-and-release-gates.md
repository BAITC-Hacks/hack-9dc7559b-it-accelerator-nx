---
id: NFR-01
owner: D1
status: in_progress
wave: 0
size: S
depends_on: []
---

# NFR-01 — Профиль 1k–5k RPS и измеримые условия релиза

**Исполнитель:** D1. **Этап:** G0. **Объём:** S. Реализация D1 в работе; непроверенные критерии не считаются выполненными.
**Зависимости для старта:** Нет — можно начать сразу.
**Покрытие:** AC-16; NFR производительность.

## Goal

Превратить число RPS и «единицы секунд» в воспроизводимую проверку.

## Context

Подтверждено только total API RPS; tool loop/vision увеличивают provider calls независимо от HTTP.

Основные решения: [единое ТЗ](../../docs/ekt-assistant-spec.md). Общий workflow и финальные gates: [tasks/README.md](../README.md).

## План реализации

1. Определить доли history/status/createTurn/SSE-open/offer/cart/upload requests и user/session distribution; сумма mix100%.
2. Зафиксировать corpus size, query lengths, average/p95 input-output tokens, model rounds, attachment sizes, active SSE/multi-tabs, queue duration.
3. Согласовать peak/sustained1k3k5k, TTFT полезного текста и completion SLO, rejection/success gates из spec; не считать202 ответом ассистента.
4. Получить актуальные RPM/TPM/budget/model access для dev/test аккаунта либо отметить конкретно неизвестные данные без выдуманных цифр.
5. Посчитать generation/embedding/vision/catalog/cart rates отдельно и planned load budget; mock/real run scenarios разграничить.
6. Передать PERF-01/02 файл профиля и перечень external prerequisites для AC-16, не блокируя функциональную разработку.

## Область изменений

docs/performance/workload.md; tests/load/profiles/; docs/ekt-assistant-spec.md при уточнении SLO

Пути с многоточием обозначают предлагаемые package/file locations, а не уже существующие файлы. Публичный контракт, env и номера миграций согласовать по ownership в README.

## Constraints

- Не запускать высокую нагрузку на shared/production или платную API без выделенного бюджета.
- Среднее T в законе Литтла не подменять p95.
- All5xx/rejected/dropped считаются, быстрые отказы не подтверждают capacity.
- Соблюдать [AGENTS.md](../../AGENTS.md), [backend/AGENTS.md](../../backend/AGENTS.md) и [frontend/AGENTS.md](../../frontend/AGENTS.md) для изменяемой области.

## Done when

- [ ] Есть versioned profiles1k3k5k с явной долей AI turns и model calls multiplier.
- [ ] Один ключ account не подразумевает бесконечную квоту; unknown quota честно записана.
- [ ] SLO отличает acceptance, queue, TTFT и законченный ответ.
- [ ] Команда согласовала, какой evidence нужен для functional vs full high-load release.
- [ ] Проверки выполнены на своей ветке; PR содержит результат проверок и известные ограничения.

## Проверка и передача

При backend-изменениях: `cd backend && ./gradlew build`; при frontend: `cd frontend && npm run build && npm run lint`. API изменился → поднять backend и выполнить `cd frontend && npm run gen`. Помимо сборки воспроизвести перечисленные выше позитивные и негативные сценарии; mocks портов не считаются финальной продуктовой приёмкой.

Проверку нового clean/upgrade DB проводить в отдельном Compose project/volume. Общую demo-БД не сбрасывать. Результаты бизнес-сценариев после интеграции входят в QA-01/QA-02; нагрузка — PERF-02.

**Передать:** D2: embedding/OCR/search budgets; D3: точный load generator mix; REL-01: external capacity gates.

**Evidence после выполнения:** заполнить commit/PR, команды, ссылки на отчёты и фактический результат; до подтверждения всех критериев статус остаётся in_progress.

**Текущий handoff:** [реализация D1 и открытые проверки](../../docs/api/d1-handoff.md). Статус `in_progress`; результаты final integration run пока pending.
