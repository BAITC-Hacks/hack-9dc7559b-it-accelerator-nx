@AGENTS.md

## Claude Code — backend
- Соблюдай слои config/security/domain/web/ai и порядок annotation processors.
- Перед `./gradlew bootRun` убедись, что БД поднята: `docker compose ps` (из корня).
- Конфиг пиши как `${ENV_VAR:дефолт-под-localhost}` — код обязан работать и с хоста, и внутри compose. Никаких `localhost` в java-коде.
- Новая env-переменная → сразу в `.env.example` и в `environment:` сервиса `backend`.
- После правки `build.gradle` контейнер надо пересобирать: `docker compose up -d --build backend`.
- Не редактируй применённые Flyway-миграции; нужен чистый старт — `docker compose down -v` (предупреди пользователя).
- После правки контроллеров/DTO напомни фронту про `npm run gen`.
