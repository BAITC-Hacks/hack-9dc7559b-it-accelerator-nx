---
id: CHAT-03
owner: D1
status: in_progress
wave: 2
size: M
depends_on: ["FOUND-01", "CHAT-01"]
---

# CHAT-03 — SSE, reconnect и защита от устаревших событий

**Исполнитель:** D1. **Этап:** G2. **Объём:** M. Реализация D1 в работе; непроверенные критерии не считаются выполненными.
**Зависимости для старта:** [FOUND-01](../00-foundation/FOUND-01-contracts-and-ports.md), [CHAT-01](../02-chat/CHAT-01-history-and-runs.md)
**Покрытие:** AC-14; NFR streaming.

## Goal

Доставлять ответ и продуктовые события без потери начала и скрытой повторной генерации.

## Context

Generated transport умеет fetch SSE, eventId и AbortSignal, но требует собственного auth/retry policy.

Основные решения: [единое ТЗ](../../docs/ekt-assistant-spec.md). Общий workflow и финальные gates: [tasks/README.md](../README.md).

## План реализации

1. Реализовать GET events для уже существующего run, union payloads из FOUND-01, ordered seq и epoch.
2. Initial subscribe читает journal с начала/курсорной позиции; final event идёт после durable result.
3. Ограничить buffer/replay TTL и память, heartbeat и slow-reader policy; normal EOF без terminal не означает completed.
4. Выдать replay_unavailable и snapshot с sequence/version, когда journal потерян; не обещать восстановленные deltas при потере буфера.
5. Отклонять stale epoch после lease loss/cancel/terminal как у publisher, так и delivery path.
6. Прогнать browser-compatible stream через actual proxy; multi-replica journal подключается в PERF-01 без смены wire DTO.

## Область изменений

backend/…/web/chat/; domain/chat/events/; config/; src/test/…/streaming/

Пути с многоточием обозначают предлагаемые package/file locations, а не уже существующие файлы. Публичный контракт, env и номера миграций согласовать по ownership в README.

## Constraints

- GET/reconnect не создаёт run и не повторяет tool calls.
- Disconnect не отменяет автоматически run; Stop отдельный endpoint.
- Не публиковать скрытые model reasoning или сырой private tool output.
- Соблюдать [AGENTS.md](../../AGENTS.md), [backend/AGENTS.md](../../backend/AGENTS.md) и [frontend/AGENTS.md](../../frontend/AGENTS.md) для изменяемой области.

## Done when

- [ ] Worker стартовал до SSE subscription — начало не теряется.
- [ ] Replay/duplicate events не повторяют текст; expired replay возвращает документированный snapshot.
- [ ] Оживший stale worker после terminal не отдаёт новых deltas/второго terminal.
- [ ] Slow reader ограничен, heartbeat работает, после failure нет утечки server resources.
- [ ] Проверки выполнены на своей ветке; PR содержит результат проверок и известные ограничения.

## Проверка и передача

При backend-изменениях: `cd backend && ./gradlew build`; при frontend: `cd frontend && npm run build && npm run lint`. API изменился → поднять backend и выполнить `cd frontend && npm run gen`. Помимо сборки воспроизвести перечисленные выше позитивные и негативные сценарии; mocks портов не считаются финальной продуктовой приёмкой.

Проверку нового clean/upgrade DB проводить в отдельном Compose project/volume. Общую demo-БД не сбрасывать. Результаты бизнес-сценариев после интеграции входят в QA-01/QA-02; нагрузка — PERF-02.

**Передать:** D3: real SSE endpoints/cursor semantics/error fixtures; PERF-01: publisher abstraction и fencing keys.

**Evidence после выполнения:** заполнить commit/PR, команды, ссылки на отчёты и фактический результат; до подтверждения всех критериев статус остаётся in_progress.

**Текущий handoff:** [реализация D1 и открытые проверки](../../docs/api/d1-handoff.md). Статус `in_progress`; результаты final integration run пока pending.
