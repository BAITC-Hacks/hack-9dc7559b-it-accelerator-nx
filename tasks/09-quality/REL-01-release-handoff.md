---
id: REL-01
owner: D3
status: todo
wave: 6
size: M
depends_on: ["QA-01", "QA-02", "PERF-02", "OPS-02"]
---

# REL-01 — Финальная интеграция, документация и поставка

**Исполнитель:** D3. **Этап:** G6. **Объём:** M. Все задачи обязательны для полного scope; отметка todo означает, что реализация не выполнена.
**Зависимости для старта:** [QA-01](../09-quality/QA-01-end-to-end-acceptance.md), [QA-02](../09-quality/QA-02-rag-and-recognition-evaluation.md), [PERF-02](../08-platform/PERF-02-load-and-failure-tests.md), [OPS-02](../08-platform/OPS-02-complete-product-compose.md)
**Покрытие:** AC-1…17; полный продукт.

## Goal

Передать проверенный Compose-продукт с честным статусом каждого требования.

## Context

Разработчики работали параллельно; финал проверяет совместимость схем, migrations, generated client и режимов.

Основные решения: [единое ТЗ](../../docs/ekt-assistant-spec.md). Общий workflow и финальные gates: [tasks/README.md](../README.md).

## План реализации

1. Собрать короткие зелёные PR в integration branch, resolve conflicts generated code только регенерацией; обновить snapshot из springdoc.
2. Запустить backend build, frontend build/lint и необходимые integration/browser/eval/load checks из итогового checkout.
3. Прогнать ./scripts/test-compose.sh на чистом isolated project, restart/upgrade и --live-ai с sample cart; зафиксировать реальные urls/readiness.
4. Сопоставить AC1…17 с evidence; отдельные статусы functional build, liveAI/quality, partner compatibility и high-load.
5. README содержит key/env setup, compose start/stop/data retention, host-demo/widget/cart links, fixture provenance, test commands и known limitations.
6. Проверить секреты/артефакты: .env ignored, logs/reports без PII; приложить screenshots и reports без приватных исходников.
7. Завершённые task statuses обновить ссылкой на commit/evidence; незавершённые критерии не объявлять выполненными ради дедлайна.

## Область изменений

README.md; docs/ekt-assistant-spec.md; docs/reports/; scripts/; CI; tasks status fields

Пути с многоточием обозначают предлагаемые package/file locations, а не уже существующие файлы. Публичный контракт, env и номера миграций согласовать по ownership в README.

## Constraints

- Не merge в main без зелёных checks и не forcepush.
- Исходный ТЗ и пользовательские изменения не терять; runtime scope сокращать только отдельным согласованным решением.
- Без доступной partner atomicity/квоты разрешён working prototype, но не заявление о полном production/NFR compliance.
- Соблюдать [AGENTS.md](../../AGENTS.md), [backend/AGENTS.md](../../backend/AGENTS.md) и [frontend/AGENTS.md](../../frontend/AGENTS.md) для изменяемой области.

## Done when

- [ ] Из README новый разработчик запускает готовое приложение через Docker Compose без ручных SQL.
- [ ] AC1…17 имеют traceable status/report, все необходимые проверки действительно выполнены.
- [ ] Default/live/mock/partner modes отличимы и не включают реальные cart writes случайно.
- [ ] Все env и generated schema синхронны; исходники/документация закоммичены осмысленно, shared DB сохранена.
- [ ] Проверки выполнены на своей ветке; PR содержит результат проверок и известные ограничения.

## Проверка и передача

При backend-изменениях: `cd backend && ./gradlew build`; при frontend: `cd frontend && npm run build && npm run lint`. API изменился → поднять backend и выполнить `cd frontend && npm run gen`. Помимо сборки воспроизвести перечисленные выше позитивные и негативные сценарии; mocks портов не считаются финальной продуктовой приёмкой.

Проверку нового clean/upgrade DB проводить в отдельном Compose project/volume. Общую demo-БД не сбрасывать. Результаты бизнес-сценариев после интеграции входят в QA-01/QA-02; нагрузка — PERF-02.

**Передать:** Команда/пользователь: готовый проверяемый продукт, команды и отчёты; при блокировках — точный список оставшихся внешних условий.

**Evidence после выполнения:** заполнить commit/PR, команды, ссылки на отчёты и фактический результат; до выполнения статус остаётся todo.
