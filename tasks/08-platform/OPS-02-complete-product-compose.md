---
id: OPS-02
owner: D3
status: todo
wave: 4
size: L
depends_on: ["OPS-01", "UI-05", "CAT-01", "RAG-01", "ATT-03"]
---

# OPS-02 — Полный Compose-продукт, seed и containerized test runner

**Исполнитель:** D3. **Этап:** G4. **Объём:** L. Все задачи обязательны для полного scope; отметка todo означает, что реализация не выполнена.
**Зависимости для старта:** [OPS-01](../08-platform/OPS-01-compose-foundation.md), [UI-05](../07-widget/UI-05-embed-and-mobile.md), [CAT-01](../03-catalog/CAT-01-import-and-schema.md), [RAG-01](../04-knowledge/RAG-01-terms-and-sources.md), [ATT-03](../06-attachments/ATT-03-pdf-ocr-and-photo.md)
**Покрытие:** AC-12/17; весь FR scope.

## Goal

Собрать запуск и проверку продукта без ручных SQL и host Java/Node/OCR.

## Context

Начальный full compose должен превратиться в воспроизводимую систему со stateful data/cart и настоящими parsers.

Основные решения: [единое ТЗ](../../docs/ekt-assistant-spec.md). Общий workflow и финальные gates: [tasks/README.md](../README.md).

## План реализации

1. Довести full services db/redis/backend/frontend, worker roles, named file storage/OCR runtime и host-demo; health dependency graph без циклов.
2. Упаковать idempotent sample catalog/FAQ initializer, versioned readiness; backend liveness отдельно от готовности индекса.
3. Реализовать test-compose.sh с unique project/свободными портами/test volumes, seed→readiness→browser/API runner→report→свой cleanup.
4. Offline profile отключает OpenAI auto-config и использует fake Llm/Embedding/Vision, но реальные domain/DB/Cart Gate/parsers/UI; никакого fake backend в финальном E2E.
5. Live-AI flag требует API key и включает real generation/embeddings/vision с sample catalog/mock cart. Partner mode отдельный switch и sandbox config.
6. Проверить full startup с новой БД и upgrade V2, restart persistence, versioned file storage и cache consistency.
7. Документировать env/commands/ports/test accounts, artifact locations и limitations; проверить proxy SSE buffering/timeouts/CORS и mobile embed.

## Область изменений

docker-compose*.yml; backend/frontend Dockerfiles; .env.example; scripts/test-compose.sh; scripts/seed-demo.*; tests/e2e/ runner image; README.md

Пути с многоточием обозначают предлагаемые package/file locations, а не уже существующие файлы. Публичный контракт, env и номера миграций согласовать по ownership в README.

## Constraints

- Default real mode без ключа fail-fast сохраняется; keyless mock только явный test profile.
- down-v удаляет исключительно test project volumes; shared pgdata не трогать.
- VITE_API_URL browser-visible и build-time; backend serviceDNS не browser URL.
- Соблюдать [AGENTS.md](../../AGENTS.md), [backend/AGENTS.md](../../backend/AGENTS.md) и [frontend/AGENTS.md](../../frontend/AGENTS.md) для изменяемой области.

## Done when

- [ ] Одна documented full command запускает demo с заполненным key и автоматическими fixtures/ready index.
- [ ] ./scripts/test-compose.sh запускает все mock integration services/tests без LLM network/key, nonzero при failures.
- [ ] ./scripts/test-compose.sh --live-ai проверяет real model + same backend/Cart mock при заданном budget.
- [ ] Новый clone требует Docker/Compose и настройки key для live, но не ручной SQL/parser installation.
- [ ] Проверки выполнены на своей ветке; PR содержит результат проверок и известные ограничения.

## Проверка и передача

При backend-изменениях: `cd backend && ./gradlew build`; при frontend: `cd frontend && npm run build && npm run lint`. API изменился → поднять backend и выполнить `cd frontend && npm run gen`. Помимо сборки воспроизвести перечисленные выше позитивные и негативные сценарии; mocks портов не считаются финальной продуктовой приёмкой.

Проверку нового clean/upgrade DB проводить в отдельном Compose project/volume. Общую demo-БД не сбрасывать. Результаты бизнес-сценариев после интеграции входят в QA-01/QA-02; нагрузка — PERF-02.

**Передать:** QA-01/PERF-02: repeatable isolated runners; пользователю: точные команды запуска и endpoints.

**Evidence после выполнения:** заполнить commit/PR, команды, ссылки на отчёты и фактический результат; до выполнения статус остаётся todo.
