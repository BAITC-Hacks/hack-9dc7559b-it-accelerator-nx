---
id: OPS-01
owner: D3
status: todo
wave: 0
size: M
depends_on: []
---

# OPS-01 — Изолированный Compose и основа режимов запуска

**Исполнитель:** D3. **Этап:** G0. **Объём:** M. Все задачи обязательны для полного scope; отметка todo означает, что реализация не выполнена.
**Зависимости для старта:** Нет — можно начать сразу.
**Покрытие:** AC-17; NFR конфигурация.

## Goal

Подготовить окружение, в котором три worktree и CI не конфликтуют с общей demo-БД.

## Context

В compose фиксированы container_name и backend/frontend host ports. Одного -p для изоляции сейчас недостаточно.

Основные решения: [единое ТЗ](../../docs/ekt-assistant-spec.md). Общий workflow и финальные gates: [tasks/README.md](../README.md).

## План реализации

1. Проверить текущий docker compose ps и localhost demo до изменений, сохранить используемый shared project/volume.
2. Параметризовать либо убрать fixed container_name, обеспечить отдельные host ports/project names в test override. Сохранить базовые db/backend/frontend DNS, profiles и default ports.
3. Добавить Redis для full/test с healthcheck и отдельным test volume; подготовить named file storage и роли worker без заведения обязательных микросервисов.
4. Определить три независимых adapter settings: LLM, catalog/stock, cart; live OpenAI не переключает автоматически реальные partner writes. Значения и новые env согласовать с D1/D2 в этом PR.
5. Создать test profile skeleton, который исключает OpenAI auto-config при mock mode; default real mode не получает fake key.
6. Проверить config/health startup и документировать host .env export; frontend VITE_API_URL передавать build args и использовать browser-accessible адрес.

## Область изменений

docker-compose.yml; docker-compose.test.yml; .env.example; backend/Dockerfile; frontend/Dockerfile; scripts/; README.md

Пути с многоточием обозначают предлагаемые package/file locations, а не уже существующие файлы. Публичный контракт, env и номера миграций согласовать по ownership в README.

## Constraints

- Никогда не down -v общего hackalem.
- Новые переменные одновременно .env.example + compose + application владельца; shared env contract не откладывать.
- Из контейнеров db:5432, с хоста localhost; не помещать секреты в frontend build.
- Соблюдать [AGENTS.md](../../AGENTS.md), [backend/AGENTS.md](../../backend/AGENTS.md) и [frontend/AGENTS.md](../../frontend/AGENTS.md) для изменяемой области.

## Done when

- [ ] docker compose config проходит для default/full/test без раскрытия секретов в отчёте.
- [ ] Два выделенных тестовых проекта имеют разные порты/containers/volumes; shared DB остаётся healthy.
- [ ] DB healthy gates Flyway; Redis readiness описана, отсутствует startup cycle frontend/backend/seed.
- [ ] Baseline full profile сохраняет прежний запуск; mock profile не совершает OpenAI вызовы.
- [ ] Проверки выполнены на своей ветке; PR содержит результат проверок и известные ограничения.

## Проверка и передача

При backend-изменениях: `cd backend && ./gradlew build`; при frontend: `cd frontend && npm run build && npm run lint`. API изменился → поднять backend и выполнить `cd frontend && npm run gen`. Помимо сборки воспроизвести перечисленные выше позитивные и негативные сценарии; mocks портов не считаются финальной продуктовой приёмкой.

Проверку нового clean/upgrade DB проводить в отдельном Compose project/volume. Общую demo-БД не сбрасывать. Результаты бизнес-сценариев после интеграции входят в QA-01/QA-02; нагрузка — PERF-02.

**Передать:** D1/D2: доступные service DNS, roles/storage/Redis settings и команды изолированного запуска; D3: основа OPS-02.

**Evidence после выполнения:** заполнить commit/PR, команды, ссылки на отчёты и фактический результат; до выполнения статус остаётся todo.
