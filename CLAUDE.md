@AGENTS.md

## Claude Code — специфика
- AGENTS.md выше = единый источник правил для всей команды. Следуй ему.
- Перед крупной задачей используй plan mode; держи изменения атомарными.
- Slash-команды: `/new-entity`, `/demo-check`, `/commit` (см. .claude/commands/).
- Для параллельной работы используй отдельный git worktree на задачу.

### Docker compose — рефлексы
- Первым делом в сессии проверь, поднято ли окружение: `docker compose ps`. Не поднято — `docker compose up -d` и дождись `(healthy)`.
- Любая ошибка «Connection refused» / «relation does not exist» → сначала `docker compose ps` и `docker compose logs`, только потом правь код.
- Добавляешь env-переменную — правь три места сразу: `.env.example`, `docker-compose.yml`, чтение в коде (с дефолтом).
- Проверяй работу на чистом состоянии перед коммитом: `docker compose down -v && docker compose up -d`.
- `docker compose down -v` стирает данные — спрашивай пользователя, если окружение может быть не только твоим.
- Не запускай деструктивные shell-команды (`rm -rf`, force-push, `docker system prune`) без явного запроса.
