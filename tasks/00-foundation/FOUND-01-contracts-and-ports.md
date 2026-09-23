---
id: FOUND-01
owner: D1
status: in_progress
wave: 0
size: M
depends_on: []
---

# FOUND-01 — Первый API-контракт, Java ports и схема handoff

**Исполнитель:** D1. **Этап:** G0. **Объём:** M. Реализация D1 в работе; непроверенные критерии не считаются выполненными.
**Зависимости для старта:** Нет — можно начать сразу.
**Покрытие:** Все FR; AC-17.

## Goal

Разблокировать трёх разработчиков общими DTO и портами до реализации доменов.

## Context

Сейчас SDK содержит ping; имеются product search/upsert и V2 products. D2/D3 не должны ждать готового agent/cart.

Основные решения: [единое ТЗ](../../docs/ekt-assistant-spec.md). Общий workflow и финальные gates: [tasks/README.md](../README.md).

## План реализации

1. Согласовать с D2/D3 ProductDetails, Money/Quantity decimal strings, OfferSnapshot, ResultSet, ProposalSnapshot, AttachmentResult, ChatEvent и ProblemDetail по единому ТЗ. Bigint IDs наружу сериализовать строками.
2. Опубликовать controller signatures, validation/security и Java ports Catalog/Stock/Analogs/Knowledge/Attachment/Cart/Llm; implementation stubs допустимы только contract/test profile.
3. Получить springdoc schema с запущенного backend, сохранить snapshot и JSON примеры accepted/delta/products/proposal/terminal/conflict/review. Не писать snapshot руками.
4. Задать contract compatibility/version policy, tool allowlist и compile-time fakes портов; fake Cart не выполняет реальные side effects.
5. Зафиксировать registry V3 (весь D1 baseline), V4/V5 (следующие D2) и владельцев общих файлов. Contract-only PR и завершение FOUND-01 не ждут всех SQL: D1/D2 публикуют schema drafts следующими ранними domain PR с последовательным merge до общего E2E. При следующих миграциях выдавать следующий ещё не применённый номер.
6. Перегенерировать SDK; передать D3 snapshot и воспроизводимый contract-profile запуск, D2 — signatures. Зафиксировать SLO/endpoint mix как versioned hypotheses, данные о partner auth/quota — pending если их нет.

## Область изменений

backend/src/main/java/com/hackalem/{web,domain,ai}/…; config/OpenApiConfig.java; docs/api/openapi.json; docs/api/fixtures/; migration registry в tasks/README.md

Пути с многоточием обозначают предлагаемые package/file locations, а не уже существующие файлы. Публичный контракт, env и номера миграций согласовать по ownership в README.

## Constraints

- Не переименовывать backend/frontend; V1/V2 неизменны.
- API-first: downstream зависит от схем, не от готовых реализаций; runtime stubs запрещены в live mode.
- Env нового test profile одновременно в application/.env.example/compose с D3; реальный режим по-прежнему требует ключ.
- Соблюдать [AGENTS.md](../../AGENTS.md), [backend/AGENTS.md](../../backend/AGENTS.md) и [frontend/AGENTS.md](../../frontend/AGENTS.md) для изменяемой области.

## Done when

- [ ] Springdoc отдаёт все согласованные union/security/error schemas; fixtures проходят schema validation.
- [ ] npm run gen и генерация из snapshot дают совместимые DTO; frontend build проходит.
- [ ] Все ports компилируются с fake implementations; D2/D3 могут реализовывать свои ветки без изменений D1.
- [ ] Контракт уточняет stock quantity, multi-line atomic cart, confirmation, unknown outcome и SSE replay; нет свободного mutating tool.
- [ ] Проверки выполнены на своей ветке; PR содержит результат проверок и известные ограничения.

## Проверка и передача

При backend-изменениях: `cd backend && ./gradlew build`; при frontend: `cd frontend && npm run build && npm run lint`. API изменился → поднять backend и выполнить `cd frontend && npm run gen`. Помимо сборки воспроизвести перечисленные выше позитивные и негативные сценарии; mocks портов не считаются финальной продуктовой приёмкой.

Проверку нового clean/upgrade DB проводить в отдельном Compose project/volume. Общую demo-БД не сбрасывать. Результаты бизнес-сценариев после интеграции входят в QA-01/QA-02; нагрузка — PERF-02.

**Передать:** D2: Java interfaces/fixtures; D3: OpenAPI snapshot и event examples; всем: первый зелёный contract PR и registry миграций.

**Evidence после выполнения:** заполнить commit/PR, команды, ссылки на отчёты и фактический результат; до подтверждения всех критериев статус остаётся in_progress.

**Текущий handoff:** [реализация D1 и открытые проверки](../../docs/api/d1-handoff.md). Статус `in_progress`; результаты final integration run пока pending.
