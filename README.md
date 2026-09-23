# HackAlem AI

ИИ-ассистент для B2B-чата на сайте ekt.kz: помогает найти товар по артикулу или описанию, проверить наличие и совместимость, ответить по условиям покупки и подготовить предложение для корзины.

Это рабочий hackathon monorepo команды TechnoHorizon. README построен вокруг воспроизводимого demo-path: от чистого checkout до проверки API без устных пояснений.

## Содержание

- [Что уже работает](#что-уже-работает)
- [Запуск проекта — подробно](#запуск-проекта--подробно)
  - [Что понадобится](#что-понадобится)
  - [Сценарий A — разработка (рекомендуется каждый день)](#сценарий-a--разработка-рекомендуется-каждый-день)
  - [Сценарий B — всё в Docker «как на проде»](#сценарий-b--всё-в-docker-как-на-проде)
  - [Сценарий C — offline без OpenAI (contract)](#сценарий-c--offline-без-openai-contract)
  - [Проверка, что всё живо](#проверка-что-всё-живо)
  - [Остановка и сброс](#остановка-и-сброс)
  - [Шпаргалка команд](#шпаргалка-команд)
- [Разработка](#разработка)
- [Переменные окружения](#переменные-окружения)
- [API и генерация клиента](#api-и-генерация-клиента)
- [Тесты](#тесты)
- [Типовые проблемы](#типовые-проблемы)
- [Документация](#документация)
- [Статус и ограничения](#статус-и-ограничения)

## Что уже работает

- Spring Boot API на Java 21 с PostgreSQL, pgvector, Flyway и Redis.
- Visitor JWT/session ACL, разговоры, история, runs и идемпотентная отправка сообщений.
- Ограниченный agent/tool loop, типизированные события и SSE replay.
- Иммутабельные предложения: предложение нужно отдельно подтвердить до изменения корзины.
- Синтетический каталог, условия покупки и fixtures для offline-проверок.
- React 19 + Vite + TypeScript frontend и генерируемый из OpenAPI клиент.
- Docker Compose для зависимостей, полного demo и изолированных тестовых окружений.
- Embed: script + iframe `/widget`, host-demo на отдельном origin (`:5180`).

Synthetic data вымышлены и не являются каталогом или коммерческими обязательствами ekt.kz. Реальные data/identity/cart adapters, production deployment, live OpenAI evaluation и high-load требуют отдельных проверок.

## Запуск проекта — подробно

Выберите один сценарий. Не смешивайте порты двух сценариев на одной машине без смены `POSTGRES_PORT` / project name.

| Сценарий | Когда | OpenAI key | Порты |
| --- | --- | --- | --- |
| **A. Dev** | Ежедневная разработка, hot reload | нужен для live-чата | БД `5432`, Redis `6379`, API `8080`, UI `5173` |
| **B. Full Docker** | Демо «как на проде», embed | обязателен | то же + host-demo `5180` |
| **C. Contract offline** | Без ключа и без LLM-сети | не нужен | БД `55432`, Redis `56379`, API `18080` |

### Что понадобится

- Git, Docker Desktop / Engine + Compose v2.
- Для сценария A: **Java 21**, **Node.js 20+**.
- Свободные порты из таблицы выше (или поменяйте их в `.env`).

Проверка инструментов:

```bash
docker --version
docker compose version
java -version    # только для A и части C
node -v          # только для A
```

Клонирование (если ещё нет репозитория):

```bash
git clone https://github.com/BAITC-Hacks/hack-9dc7559b-it-accelerator-nx.git
cd hack-9dc7559b-it-accelerator-nx
```

### Сценарий A — разработка (рекомендуется каждый день)

БД и Redis в Docker; backend и frontend на хосте с hot reload.

#### A1. Создать `.env`

```bash
cp .env.example .env
```

Откройте `.env` и задайте минимум:

```dotenv
OPENAI_API_KEY=sk-ваш-ключ
```

Остальное можно не трогать. Ключ **не коммитьте**.

Если порт `5432` занят локальным Postgres:

```dotenv
POSTGRES_PORT=5433
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5433/hackalem
```

#### A2. Поднять только инфраструктуру

Из **корня** репозитория:

```bash
docker compose up -d
docker compose ps
```

Ожидание: `db` и `redis` в статусе `(healthy)`. Если нет — смотрите логи:

```bash
docker compose logs -f db
docker compose logs -f redis
```

#### A3. Backend

Новый терминал:

```bash
cd backend
./gradlew bootRun
```

Первый запуск Gradle может идти несколько минут. Успех: в логе есть `Started HackalemApplication` и порт `8080`.

Проверка:

```bash
curl --fail http://localhost:8080/actuator/health
```

#### A4. Frontend

Ещё один терминал:

```bash
cd frontend
npm ci          # или npm install при первом запуске
npm run dev
```

Откройте http://localhost:5173 — чат UI.

Для учебного mock-режима UI (без живого backend-чата):

```bash
npm run dev:mock
```

откроется на http://localhost:5174.

#### A5. Что куда ходит

- Браузер → frontend `5173`, API-запросы на `VITE_API_URL` (= `http://localhost:8080`).
- Backend на хосте → Postgres `localhost:5432` (или ваш `POSTGRES_PORT`), Redis `localhost:6379`.
- Не используйте hostname `db` / `backend` из браузера — они видны только внутри Docker-сети.

### Сценарий B — всё в Docker «как на проде»

Одной командой: Postgres, Redis, backend, frontend (nginx) и host-demo для iframe.

#### B1. `.env` с ключом

```bash
cp .env.example .env
# в .env: OPENAI_API_KEY=sk-ваш-ключ
```

Без ключа backend в профиле `live` упадёт на старте — это ожидаемо.

#### B2. Сборка и запуск

```bash
docker compose --profile full up -d --build
docker compose ps
```

Дождитесь `healthy` у `backend` и `frontend` (первый build долгий).

#### B3. Открыть в браузере

| Что | URL |
| --- | --- |
| Чат (SPA) | http://localhost:5173 |
| Embed на «чужом» сайте | http://localhost:5180 → кнопка «Чат» |
| Корзина | http://localhost:5173/cart |
| Swagger | http://localhost:8080/swagger-ui.html |
| OpenAPI JSON | http://localhost:8080/v3/api-docs |
| Health | http://localhost:8080/actuator/health |

Проверка с терминала:

```bash
curl --fail http://localhost:8080/actuator/health
curl --fail -o /dev/null -w "%{http_code}\n" http://localhost:5173/
curl --fail -o /dev/null -w "%{http_code}\n" http://localhost:5180/
curl --fail http://localhost:5173/embed/v1/widget.js | head -c 80
```

#### B4. Пересборка после смены env фронта

`VITE_*` запекаются **при сборке образа**. Поменяли `VITE_API_URL` или `VITE_EMBED_ALLOWED_ORIGINS` → обязательно:

```bash
docker compose up -d --build frontend
```

Простого `restart` недостаточно.

### Сценарий C — offline без OpenAI (contract)

Отдельный Compose-проект и порты, **не трогает** общий volume `hackalem_pgdata`. Ключ не нужен.

#### C1. БД + Redis для D1

```bash
docker compose -p hackalem-d1 -f scripts/d1-compose.yml up -d --wait
```

Порты: Postgres `55432`, Redis `56379`.

#### C2. Backend в профиле `contract`

Нужны Java 21 и Gradle wrapper:

```bash
cd backend
SPRING_PROFILES_ACTIVE=contract \
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:55432/hackalem \
REDIS_PORT=56379 \
PORT=18080 \
./gradlew bootRun
```

#### C3. Проверка

```bash
curl --fail http://localhost:18080/actuator/health
curl --fail -X POST http://localhost:18080/auth/visitor-session
```

Остановка только этого стека (volume D1 сохраняется):

```bash
docker compose -p hackalem-d1 -f scripts/d1-compose.yml down
```

### Проверка, что всё живо

Минимум после любого сценария A/B:

```bash
# инфраструктура
docker compose ps

# API
curl --fail http://localhost:8080/actuator/health
curl --fail -X POST http://localhost:8080/auth/visitor-session

# фронт (если запущен)
curl --fail -o /dev/null -w "%{http_code}\n" http://localhost:5173/
```

Ожидание health: JSON со статусом `UP`. Visitor-session: `2xx` и тело с токеном/сессией.

### Остановка и сброс

| Цель | Команда | Данные БД |
| --- | --- | --- |
| Остановить контейнеры, данные оставить | `docker compose down` | живы (`pgdata`) |
| Полный сброс **своей** demo-БД | `docker compose down -v && docker compose up -d` | **стёрты** |
| Только логи | `docker compose logs -f backend` | — |
| psql внутрь | `docker compose exec db psql -U hackalem -d hackalem` | — |

**Не** делайте `down -v` на общем/чужом окружении без предупреждения команды.

Изолированный тестовый слот (не shared `hackalem`):

```bash
sh scripts/isolated-compose.sh my-agent-slot1 1 up    # поднять
sh scripts/isolated-compose.sh my-agent-slot1 1 ps
sh scripts/isolated-compose.sh my-agent-slot1 1 down  # остановить; свой volume, не общий
```

Имя проекта не должно быть `hackalem`. Слот `1..9` задаёт сдвинутые порты.

### Шпаргалка команд

Из корня репозитория:

```bash
# Инфра
docker compose up -d
docker compose ps
docker compose logs -f db

# Full stack
docker compose --profile full up -d --build
docker compose up -d --build backend
docker compose up -d --build frontend
docker compose down

# Dev-процессы (хост)
cd backend && ./gradlew bootRun
cd frontend && npm ci && npm run gen && npm run dev

# Сборка / проверки
cd backend && ./gradlew build
cd frontend && npm run build && npm run lint && npm run test
./scripts/validate-demo-data.sh
```

## Разработка

Docker нужен для PostgreSQL и Redis. Backend и frontend обычно запускают на host (сценарий A).

### Backend

```bash
docker compose up -d
cd backend
./gradlew bootRun
```

Backend: http://localhost:8080. По умолчанию: PostgreSQL `localhost:5432`, Redis `localhost:6379`, профиль `live`.

```bash
./gradlew test
./gradlew build
```

### Frontend

```bash
cd frontend
npm ci
npm run gen      # нужен живой backend на :8080
npm run dev
```

Frontend: http://localhost:5173. `VITE_API_URL` — build-time: после смены перезапустите Vite или пересоберите Docker image.

```bash
npm run build
npm run lint
npm run test
npm run preview
```

После **любой** правки API:

```bash
# backend уже на :8080
cd frontend && npm run gen
```

`frontend/src/client/**` руками не править.

## Переменные окружения

Единый шаблон: [.env.example](.env.example). Скопируйте в `.env` и правьте локально.

| Переменная | Назначение | Default |
| --- | --- | --- |
| `POSTGRES_DB` / `USER` / `PASSWORD` | База | `hackalem` |
| `POSTGRES_PORT` | Postgres на host | `5432` |
| `REDIS_PORT` | Redis на host | `6379` |
| `BACKEND_PORT` / `FRONTEND_PORT` | API / UI в Compose | `8080` / `5173` |
| `HOST_DEMO_PORT` | Embed host-demo | `5180` |
| `OPENAI_API_KEY` | Live OpenAI | обязателен для `live` |
| `SPRING_PROFILES_ACTIVE` | Spring profile | `live` |
| `VITE_API_URL` | API **из браузера** (build-time) | `http://localhost:8080` |
| `VITE_EMBED_ALLOWED_ORIGINS` | Parent origins для postMessage | `5180,5173` |
| `EMBED_FRAME_ANCESTORS` | CSP frame-ancestors (runtime nginx) | `'self' …5180 …5173` |
| `CORS_ALLOWED_ORIGINS` | CORS backend | `5173,5180` |
| `CART_MODE` | Режим корзины | `sample` |

Адреса по контексту:

- host (`bootRun`) → БД `localhost:5432`;
- backend в Compose → БД `db:5432`;
- браузер → API `localhost:8080`, **не** `backend:8080`.

Новая переменная → сразу в `.env.example` и `docker-compose.yml`.

## Структура проекта

```text
backend/       Spring Boot API, миграции, security, agent и adapters
frontend/      React/Vite SPA, UI и сгенерированный OpenAPI client
data/          синтетический каталог, условия покупки и fixtures
host-demo/     статическая страница для проверки iframe/embed
docs/          архитектура, API handoff, performance и product spec
scripts/       Compose-сценарии, smoke/evaluation и валидаторы данных
tasks/         функциональный backlog
tests/         evaluation и load-сценарии
```

## API и генерация клиента

Контракт сначала на backend. После старта API:

```bash
cd frontend
npm run gen
npm run gen:check
```

По умолчанию input — `http://localhost:8080/v3/api-docs`. Для contract-backend:

```bash
npm run gen -- --input http://localhost:18080/v3/api-docs
```

Основные endpoint:

```text
GET  /actuator/health
GET  /v3/api-docs
POST /auth/visitor-session
```

Swagger UI: http://localhost:8080/swagger-ui.html

## Тесты

Перед передачей изменений:

```bash
cd backend && ./gradlew build
cd ../frontend && npm run build && npm run lint && npm run test
cd ..
./scripts/validate-demo-data.sh
```

Контейнерный smoke:

```bash
docker compose --profile full up -d --build
docker compose ps
curl --fail http://localhost:8080/actuator/health
docker compose down
```

## Типовые проблемы

### port is already allocated

Занят порт. В `.env` смените `POSTGRES_PORT`, `REDIS_PORT`, `BACKEND_PORT` или `FRONTEND_PORT` и синхронно `SPRING_DATASOURCE_URL` / `VITE_API_URL`, затем перезапустите сервисы.

### Backend не подключается к БД или Redis

Сначала инфра:

```bash
docker compose up -d
docker compose ps
```

В Compose hostname БД — `db`, Redis — `redis`. `localhost` внутри контейнера — это сам контейнер, не хост.

### Flyway checksum mismatch

Не редактируйте уже применённую миграцию — добавьте новую `V{n}__….sql`. На **своей** disposable-базе: `docker compose down -v && docker compose up -d`.

### npm run gen не получает OpenAPI

```bash
curl --fail http://localhost:8080/v3/api-docs | head -c 200
```

Backend должен быть уже запущен и прожевать старт.

### Изменения frontend не видны в контейнере

```bash
docker compose up -d --build frontend
```

Особенно важно для `VITE_*`.

### Backend падает из-за OpenAI

Для `live` проверьте `OPENAI_API_KEY` в `.env`. Без ключа используйте [сценарий C](#сценарий-c--offline-без-openai-contract).

### Embed / host-demo не открывает iframe

1. Frontend на `5173` отдаёт `/embed/v1/widget.js`.
2. Host-demo на `5180` грузит script с `localhost:5173`.
3. В `.env` / build args есть `VITE_EMBED_ALLOWED_ORIGINS` и `EMBED_FRAME_ANCESTORS` с origin `5180`.

## Документация

- [AGENTS.md](AGENTS.md) — правила monorepo, Compose-контракт, env и workflow.
- [backend/AGENTS.md](backend/AGENTS.md) — backend-конвенции, Flyway, security и Spring AI.
- [frontend/AGENTS.md](frontend/AGENTS.md) — frontend-конвенции и генерация клиента.
- [docs/ekt-assistant-spec.md](docs/ekt-assistant-spec.md) — единое ТЗ и целевая архитектура.
- [docs/api/README.md](docs/api/README.md) — API v1 и границы D1/D2.
- [docs/api/d1-handoff.md](docs/api/d1-handoff.md) — offline demo-path и evidence.
- [host-demo/README.md](host-demo/README.md) — embed host page.
- [tasks/README.md](tasks/README.md) — backlog.
- [data/README.md](data/README.md) — синтетические данные и валидация.

## Статус и ограничения

Текущая реализация — активная hackathon-разработка, не production-ready релиз. Отдельно требуют проверки:

- реальные catalog/stock, identity и partner cart adapters;
- полноценный production widget и embed acceptance (QA-01);
- live OpenAI smoke/evaluation и контроль стоимости;
- нагрузка 1–5 тысяч суммарных API RPS;
- production secrets, observability, deployment и disaster recovery.

Синтетические данные:

```bash
./scripts/validate-demo-data.sh
```

## Правила изменений

1. Сначала проверьте demo-path и не ломайте работающий Compose.
2. API меняйте по цепочке: backend → `/v3/api-docs` → `npm run gen` → frontend.
3. Не коммитьте секреты; `.env` остаётся локальным.
4. Применённые Flyway-миграции не редактируйте.
5. Новые env-переменные добавляйте в `.env.example` и `docker-compose.yml`.
6. Перед PR: backend `build`, frontend `build`/`lint`/`test`, smoke healthcheck.

Для инженерных ограничений используйте [AGENTS.md](AGENTS.md) как источник правил репозитория.
