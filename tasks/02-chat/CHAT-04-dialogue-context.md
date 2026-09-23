---
id: CHAT-04
owner: D1
status: todo
wave: 3
size: M
depends_on: ["CHAT-02"]
---

# CHAT-04 — Продолжение подбора и привязка пользовательского выбора

**Исполнитель:** D1. **Этап:** G3. **Объём:** M. Все задачи обязательны для полного scope; отметка todo означает, что реализация не выполнена.
**Зависимости для старта:** [CHAT-02](../02-chat/CHAT-02-agent-tools.md)
**Покрытие:** FR-7; AC-9/10.

## Goal

Сохранять смысл коротких реплик и версий выбранных вариантов.

## Context

LLM summary не гарантирует, какие товары пользователь видел первыми; нужен явный state.

Основные решения: [единое ТЗ](../../docs/ekt-assistant-spec.md). Общий workflow и финальные gates: [tasks/README.md](../README.md).

## План реализации

1. Сохранять category/budget/hard attributes/quantity/unit, immutable lastResultSet IDs/order, выбранный fulfillment option и active proposal.
2. Разрешать «первые два» по конкретному ранее показанному resultSet; несколько возможных списков требуют уточнения.
3. «Дешевле» сохраняет hard constraints; «нужно 20» меняет quantity и supersedes старое proposal, не подтверждает его.
4. Не переспрашивать известные поля; exact SKU сразу переходит к фактам, недостающие критические параметры вызывают один целевой вопрос.
5. Включить selected/reviewed attachment items в тот же state с version и без auto-cart.
6. Контролировать concurrent updates optimistic version; stale UI decision возвращает conflict и актуальное состояние.

## Область изменений

backend/…/domain/chat/DialogueState…; ai/agent/ContextBuilder…; src/test/…/dialogue/

Пути с многоточием обозначают предлагаемые package/file locations, а не уже существующие файлы. Публичный контракт, env и номера миграций согласовать по ownership в README.

## Constraints

- Порядок продуктов не восстанавливать новым random search.
- Любая смена состава/цены/количества требует нового cart snapshot и согласия.
- Модель не может менять ownership или считать чужой resultSet текущим.
- Соблюдать [AGENTS.md](../../AGENTS.md), [backend/AGENTS.md](../../backend/AGENTS.md) и [frontend/AGENTS.md](../../frontend/AGENTS.md) для изменяемой области.

## Done when

- [ ] AC-10 проходит последовательность «дешевле → сравни первые два → 20 штук» без потери исходных ограничений.
- [ ] Changed ranking не меняет смысл ordinal из прошлого resultSet.
- [ ] Две вкладки со stale state не подтверждают чужой/старый набор.
- [ ] После reload сохраняются контекст и выбранные варианты.
- [ ] Проверки выполнены на своей ветке; PR содержит результат проверок и известные ограничения.

## Проверка и передача

При backend-изменениях: `cd backend && ./gradlew build`; при frontend: `cd frontend && npm run build && npm run lint`. API изменился → поднять backend и выполнить `cd frontend && npm run gen`. Помимо сборки воспроизвести перечисленные выше позитивные и негативные сценарии; mocks портов не считаются финальной продуктовой приёмкой.

Проверку нового clean/upgrade DB проводить в отдельном Compose project/volume. Общую demo-БД не сбрасывать. Результаты бизнес-сценариев после интеграции входят в QA-01/QA-02; нагрузка — PERF-02.

**Передать:** D3: response state для выбора/сравнения; CAT-02/04: TrustedSearchContext с resultSet references.

**Evidence после выполнения:** заполнить commit/PR, команды, ссылки на отчёты и фактический результат; до выполнения статус остаётся todo.
