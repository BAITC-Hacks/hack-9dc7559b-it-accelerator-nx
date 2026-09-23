# HackAlem AI — Monorepo Agent Guide

AI-агентское решение на хакатон (5 часов). Приоритет: **рабочая demo раньше идеальности.**
Меняешь что-то — сначала проверь, что demo-path всё ещё запускается и деплой жив.

## Stack (кратко)
- Backend: Java 21, Spring Boot 3.x, Gradle (Groovy DSL), PostgreSQL + Flyway, Spring AI (OpenAI).
- Frontend: React + Vite + TypeScript, Tailwind, shadcn/ui, TanStack Query, axios, TS-клиент из OpenAPI.
- Infra: **docker compose** (Postgres + pgvector, опционально backend/frontend), деплой на Railway (fallback Render).

## Структура
- `backend/`            — Spring Boot API (см. backend/AGENTS.md)
- `frontend/`           — React SPA (см. frontend/AGENTS.md)
- `docker-compose.yml`  — оркестрация: `db` всегда, `backend`/`frontend` под профилем `full`
- `backend/Dockerfile`, `frontend/Dockerfile`, `*/.dockerignore`, `frontend/nginx.conf`
- `.env.example`        — все нужные переменные окружения (шаблон для `.env`)

---

## Старт с нуля (новый агент / новая машина)
```bash
cp .env.example .env        # заполнить OPENAI_API_KEY (остальное имеет дефолты)
docker compose up -d        # Postgres + pgvector, ждём healthy
docker compose ps           # db должен быть (healthy)
```
Дальше — dev-режим (быстрая итерация, hot reload):
```bash
cd backend && ./gradlew bootRun     # :8080, ходит в docker-овый Postgres на localhost:5432
cd frontend && npm run dev          # :5173
```
Или всё в контейнерах (проверка «как на проде», перед демо/деплоем):
```bash
docker compose --profile full up -d --build
```

## Docker Compose — контракт (менять осознанно, синхронно во всех файлах)

| Сервис     | Образ / build      | Порт (host:container) | Профиль | DNS-имя внутри сети |
|------------|--------------------|-----------------------|---------|---------------------|
| `db`       | `pgvector/pgvector:pg16` | `5432:5432`     | default | `db`                |
| `backend`  | build `./backend`  | `8080:8080`           | `full`  | `backend`           |
| `frontend` | build `./frontend` | `5173:80`             | `full`  | `frontend`          |
| `host-demo`| `nginx:alpine` + `./host-demo` | `5180:80`   | `full`  | `host-demo`         |

Правила, на которые опирается код:
- **Имя сервиса = хост внутри сети.** Переименовал `db` → обязан поправить `SPRING_DATASOURCE_URL` и `.env.example`.
- **Два адреса одной БД:** из контейнера — `db:5432`, с хоста (bootRun, psql, IDE) — `localhost:5432`. Никогда не хардкодь ни тот, ни другой в коде — только env с дефолтом под хост.
- `db` имеет **healthcheck** (`pg_isready`); `backend` стартует через `depends_on: { db: { condition: service_healthy } }`. Без этого Flyway падает на старте гонкой.
- `backend` имеет healthcheck на `/actuator/health`; `frontend` зависит от него так же.
- Данные БД — в именованном volume `pgdata`. `docker compose down` его НЕ трогает, `down -v` стирает.
- Compose сам читает `.env` из корня. Все значения — с дефолтами вида `${POSTGRES_USER:-hackalem}`, чтобы `docker compose up` работал даже без `.env`.

## Переменные окружения (единый источник — `.env.example`)
| Переменная | Кто читает | Дефолт |
|---|---|---|
| `POSTGRES_DB` / `POSTGRES_USER` / `POSTGRES_PASSWORD` | db | `hackalem` / `hackalem` / `hackalem` |
| `SPRING_DATASOURCE_URL` | backend | host: `jdbc:postgresql://localhost:5432/hackalem`, compose: `jdbc:postgresql://db:5432/hackalem` |
| `SPRING_DATASOURCE_USERNAME` / `_PASSWORD` | backend | `hackalem` |
| `OPENAI_API_KEY` | backend | **нет дефолта, обязателен** |
| `JWT_SECRET` | backend | dev-значение из `.env.example` |
| `CORS_ALLOWED_ORIGINS` | backend | `http://localhost:5173,http://localhost:5180` |
| `VITE_API_URL` | frontend (**build-time!**) | `http://localhost:8080` |
| `VITE_EMBED_ALLOWED_ORIGINS` | frontend (**build-time!**) | `http://localhost:5180,http://localhost:5173` |
| `EMBED_FRAME_ANCESTORS` | frontend nginx (runtime) | `'self' http://localhost:5180 http://localhost:5173` |
| `HOST_DEMO_PORT` | host-demo | `5180` |

Добавил новую переменную → **сразу** допиши её в `.env.example` и в `docker-compose.yml`. Переменная, которой нет в `.env.example`, считается несуществующей: у соседнего агента всё упадёт.

## Команды (из корня)
- Поднять БД:            `docker compose up -d`
- Статус / логи:         `docker compose ps` · `docker compose logs -f db`
- Всё в контейнерах:     `docker compose --profile full up -d --build`
- Пересобрать сервис:    `docker compose up -d --build backend`
- Стоп (данные живы):    `docker compose down`
- **Полный ресет БД:**   `docker compose down -v && docker compose up -d`
- psql внутрь:           `docker compose exec db psql -U hackalem -d hackalem`
- Backend dev:           `cd backend && ./gradlew bootRun`
- Frontend dev:          `cd frontend && npm run dev`
- Регенерация клиента (после ЛЮБОЙ правки API): `cd frontend && npm run gen`

## Troubleshooting (сначала сюда, потом в панику)
- **`port 5432 already in use`** — локальный Postgres занял порт. Останови его или поменяй маппинг на `5433:5432` + поправь `SPRING_DATASOURCE_URL`.
- **Flyway: `checksum mismatch` / миграция не видна** — ты правил применённую миграцию. Откати правку либо `docker compose down -v && docker compose up -d`.
- **Backend не видит БД (`Connection refused`)** — из контейнера ходишь на `localhost`. Нужен `db:5432`. Проверь: `docker compose exec backend env | grep DATASOURCE`.
- **`npm run gen` падает** — backend не поднят или ещё не прожевал старт. `curl localhost:8080/v3/api-docs` должен отдавать JSON.
- **Изменил зависимости (build.gradle / package.json), а в контейнере старое** — нужен `--build`, кеш слоёв не инвалидируется сам.
- **`db` вечно `starting`** — смотри `docker compose logs db`; чаще всего volume от другой версии Postgres → `down -v`.

## Workflow (API-first)
1. Сначала правим OpenAPI-контракт (springdoc отдаёт `/v3/api-docs`, Swagger UI на `/swagger-ui.html`).
2. Backend реализует контракт; frontend генерирует клиент `npm run gen` и потребляет его.
3. Никогда не пишем HTTP-типы на фронте руками — только сгенерированный клиент.

## Git
- Trunk-based, короткие ветки от `main`. Параллельные агенты — в отдельных git worktrees.
- Conventional Commits: `feat:`, `fix:`, `chore:`, `docs:`, `refactor:`.
- Коммит минимум раз в час. **Не ломать `main`** — мерж только с зелёной сборкой.
- Малые PR/диффы, по одной задаче за раз.
- Параллельные worktrees делят одну БД и порты 5432/8080/5173 — второй агент либо переиспользует запущенный compose, либо поднимает свой с другим `COMPOSE_PROJECT_NAME` и сдвинутыми портами.

## Definition of Done
- [ ] Код компилируется: backend `./gradlew build`, frontend `npm run build`.
- [ ] **`docker compose up -d` с чистого состояния (`down -v`) поднимает проект без ручных шагов.**
- [ ] Новые env-переменные есть в `.env.example` и в `docker-compose.yml`.
- [ ] Демо-сценарий работает end-to-end локально.
- [ ] При правке API — клиент перегенерирован (`npm run gen`), фронт использует новые типы.
- [ ] Изменения закоммичены осмысленным сообщением.

## Запреты (hard rules)
- НЕ коммитить секреты. `.env` — только локально (обязан быть в `.gitignore`); в репо лежит `.env.example`.
- НЕ редактировать уже применённые Flyway-миграции — добавлять новую `V{n}__*.sql`.
- НЕ править сгенерированные файлы (`frontend/src/client/**`) руками.
- НЕ хардкодить хосты/порты/креды в коде — только env с дефолтом.
- НЕ запускать `docker compose down -v` на чужом/общем окружении без предупреждения — это стирает данные демо.
- НЕ делать force-push в `main`.

## Паттерн промпта для задач
Goal / Context / Constraints / Done when — формулируй задачу этими 4 блоками.
