---
id: CHAT-01
owner: D1
status: in_progress
wave: 1
size: L
depends_on: ["FOUND-01", "AUTH-01"]
---

# CHAT-01 — Разговоры, история, durable runs и идемпотентность

**Исполнитель:** D1. **Этап:** G1. **Объём:** L. Реализация D1 в работе; непроверенные критерии не считаются выполненными.
**Зависимости для старта:** [FOUND-01](../00-foundation/FOUND-01-contracts-and-ports.md), [AUTH-01](../01-identity/AUTH-01-session-and-ownership.md)
**Покрытие:** FR-7; AC-10/14.

## Goal

Надёжно принимать сообщения и восстанавливать состояние независимо от HTTP-соединения.

## Context

Нужны conversation/message/run/job; DB schema берётся из общего migration gate, бизнес-код ещё отсутствует.

Основные решения: [единое ТЗ](../../docs/ekt-assistant-spec.md). Общий workflow и финальные gates: [tasks/README.md](../README.md).

## План реализации

1. Реализовать conversations/messages с cursor pagination, stable sequence и ограниченным page size.
2. После ownership искать idempotency result/hash до нового admission reservation; для нового turn атомарно создать message/run/job и вернуть 202 после commit.
3. Ограничить одним active run на conversation через constraint/CAS; конфликт payload с тем же ключом возвращает 409.
4. Сделать job claim/lease/epoch, bounded executor, total deadline, cancel и terminal transitions; запуск из БД после restart.
5. Persist partial пакетами по time/size, final message и metadata до terminal signal; connection/transaction не удерживать на provider I/O.
6. Восстановление unknown provider outcome — явный failure/reconciliation policy, не автоматический повтор платного вызова.

## Область изменений

backend/…/domain/chat/; web/chat/; src/main/resources/db/migration/; src/test/…/chat/

Пути с многоточием обозначают предлагаемые package/file locations, а не уже существующие файлы. Публичный контракт, env и номера миграций согласовать по ownership в README.

## Constraints

- Новые migrations только из registry, не переписывать V1/V2.
- Дубликат принятого запроса должен вернуть прежний run даже при заполненной очереди.
- Длительная генерация не держит JDBC connection, unlimited очередь запрещена.
- Соблюдать [AGENTS.md](../../AGENTS.md), [backend/AGENTS.md](../../backend/AGENTS.md) и [frontend/AGENTS.md](../../frontend/AGENTS.md) для изменяемой области.

## Done when

- [ ] Concurrent duplicate submit создаёт ровно один message/run; key+другой payload даёт 409.
- [ ] Reload восстанавливает историю, порядок устойчив при paging; чужая история закрыта.
- [ ] Queued job переживает restart; old lease не коммитит; cancel/completion имеет один terminal outcome.
- [ ] Тест заполненной очереди не мешает replay принятого запроса и не резервирует budget повторно.
- [ ] Проверки выполнены на своей ветке; PR содержит результат проверок и известные ограничения.

## Проверка и передача

При backend-изменениях: `cd backend && ./gradlew build`; при frontend: `cd frontend && npm run build && npm run lint`. API изменился → поднять backend и выполнить `cd frontend && npm run gen`. Помимо сборки воспроизвести перечисленные выше позитивные и негативные сценарии; mocks портов не считаются финальной продуктовой приёмкой.

Проверку нового clean/upgrade DB проводить в отдельном Compose project/volume. Общую demo-БД не сбрасывать. Результаты бизнес-сценариев после интеграции входят в QA-01/QA-02; нагрузка — PERF-02.

**Передать:** D3: working history/send/status/cancel; CHAT-02/03: run lifecycle и события сохранения.

**Evidence после выполнения:** заполнить commit/PR, команды, ссылки на отчёты и фактический результат; до подтверждения всех критериев статус остаётся in_progress.

**Текущий handoff:** [реализация D1 и открытые проверки](../../docs/api/d1-handoff.md). Статус `in_progress`; результаты final integration run пока pending.
