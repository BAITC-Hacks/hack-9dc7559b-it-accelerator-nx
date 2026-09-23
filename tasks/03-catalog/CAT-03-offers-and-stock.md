---
id: CAT-03
owner: D2
status: done
wave: 2
size: M
depends_on: ["CAT-01"]
---

# CAT-03 — Актуальные цены, остатки и склады

**Исполнитель:** D2. **Этап:** G2. **Объём:** M. Все задачи обязательны для полного scope; отметка todo означает, что реализация не выполнена.
**Зависимости для старта:** [CAT-01](../03-catalog/CAT-01-import-and-schema.md)
**Покрытие:** FR-1/2/4; AC-1/5/6/9.

## Goal

Предоставить authoritative snapshots для консультации и Confirm Gate.

## Context

Числовых остатков в V2 нет; нужна независимая семантика данных и ошибок источника.

Основные решения: [единое ТЗ](../../docs/ekt-assistant-spec.md). Общий workflow и финальные gates: [tasks/README.md](../README.md).

## План реализации

1. Реализовать StockPort/OfferSnapshot по FOUND-01: quantity/unit, price/currency, warehouse, observedAt/sourceVersion, unknown/failure.
2. Сделать sample adapter с изменяемыми test offers и partner HTTP adapter scaffold с contract fixtures; доступ/price context server-only.
3. Зафиксировать fulfillment policy: какие склады можно объединять, unit conversion, minimum и step; не суммировать недоступные склады.
4. Batch getOffers с bounded concurrency/deadline; per-product failure не превращается в zero.
5. Отделить cached stable product facts от live quotes; для confirmation recheck bypasses stale cache; cache key учитывает customer pricing.
6. Передать D1 interface для conditional authoritative cart validation и набор stock12→7/price_changed сценариев.

## Область изменений

backend/…/domain/catalog/Offer…; integration/catalog/; integration/stock/; config/; src/test/…/offers/

Пути с многоточием обозначают предлагаемые package/file locations, а не уже существующие файлы. Публичный контракт, env и номера миграций согласовать по ownership в README.

## Constraints

- Цена/остаток из RAG или model text не authority.
- Локальный lock не даёт guarantee внешнего API; atomic cart условие проверяется CART-03.
- Credentials и URLs через env с синхронным compose/.env.example.
- Соблюдать [AGENTS.md](../../AGENTS.md), [backend/AGENTS.md](../../backend/AGENTS.md) и [frontend/AGENTS.md](../../frontend/AGENTS.md) для изменяемой области.

## Done when

- [ ] IN_STOCK/OUT_OF_STOCK/ON_ORDER/UNKNOWN различимы; source timeout виден пользователю.
- [ ] Card/proposal числа совпадают с trusted snapshots для всех fixtures.
- [ ] Quantity unit/step/warehouse правила валидируются, fractional value не округляется молча.
- [ ] Price scopes разных клиентов не смешиваются в cache; confirm использует свежую версию.
- [ ] Проверки выполнены на своей ветке; PR содержит результат проверок и известные ограничения.

## Проверка и передача

При backend-изменениях: `cd backend && ./gradlew build`; при frontend: `cd frontend && npm run build && npm run lint`. API изменился → поднять backend и выполнить `cd frontend && npm run gen`. Помимо сборки воспроизвести перечисленные выше позитивные и негативные сценарии; mocks портов не считаются финальной продуктовой приёмкой.

Проверку нового clean/upgrade DB проводить в отдельном Compose project/volume. Общую demo-БД не сбрасывать. Результаты бизнес-сценариев после интеграции входят в QA-01/QA-02; нагрузка — PERF-02.

**Передать:** D1: authoritative quote contract + race fixtures; CAT-04: quantities для комплектации; D3: freshness/unknown display.

**Evidence после выполнения:** заполнить commit/PR, команды, ссылки на отчёты и фактический результат; до выполнения статус остаётся todo.

## Evidence — 23.09.2026, CAT services integration

Реализация передана в `codex/d2-catalog-services`; описание API и границ: [catalog handoff](../../docs/catalog/services-handoff.md). Реальные D1 ports подключены без изменений кода корзины.

Проверено: `DOCKER_HOST=unix:///Users/zubanyszarylkasynov/.docker/run/docker.sock TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock ./gradlew build --no-daemon` — **PASS**, включая существующие D1 regression tests и новые PostgreSQL/Redis Testcontainers. В catalog integration suite проверены exact/no embedding, missing SKU, typo/hard constraints, 12→7 и изменение цены без сброса при чтении, неизвестный/закрытый склад, дробный шаг, реальный SQL timeout, отказ embedding, 12+8/full20, отсутствие cart writes, ownership и стабильность ordinal snapshots.

Следующие gates остаются интегратору: объединить SQL draft в общую следующую миграцию, регенерировать SDK и прогнать итоговый HTTP/Compose QA-02. Синтетические embeddings не подтверждают real-model recall/latency; реальный partner stock API и partner compatibility rules не предоставлены и не проверены. До этих gates статус остаётся `in_progress`.


## Итоговая интеграция D2

Объединено в `codex/d2-remaining-services`: V5, D1 JWT/ports, актуальный OpenAPI и generated SDK.
Полная backend/frontend сборка и отдельный Compose HTTP runner проверены.
[Итоговый отчёт и failed quality gates](../../docs/reports/d2-integration.md).
