# hack-9dc7559b-it-accelerator-nx

Hackathon team repository for TechnoHorizon.
Кейс: **ИИ-ассистент для чата на сайте ekt.kz**.

Стек: Spring Boot 3.5 (Java 21, Gradle) + PostgreSQL/pgvector + Flyway + Spring AI ·
React 19 + Vite + TypeScript + Tailwind + TanStack Query.

## Быстрый старт D1 (offline backend demo)

Java 21 и Docker обязательны. Из корня запустить изолированные PostgreSQL + Redis:

```bash
docker compose -p hackalem-d1 -f scripts/d1-compose.yml up -d --wait
cd backend
SPRING_PROFILES_ACTIVE=contract SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:55432/hackalem REDIS_PORT=56379 PORT=18080 ./gradlew bootRun
```

В другом терминале:

```bash
curl --fail http://localhost:18080/actuator/health
curl --fail -X POST http://localhost:18080/auth/visitor-session
cd frontend
npm run gen -- --input ../docs/api/openapi.json
npm run build
npm run lint
```

`contract` не требует платного key, использует два test SKU и stateful sample cart. D1 dependency stack не поднимает frontend. API/сценарий proposal→confirm→cart: [D1 handoff](docs/api/d1-handoff.md); schema: [OpenAPI](docs/api/openapi.json). Frontend пока содержит исходный shell, полный widget интегрирует D3.

Общий Compose-контракт сохраняется: `docker compose up -d` — зависимости, `docker compose --profile full up -d --build` — контейнерный запуск. Live требует `OPENAI_API_KEY` и настроенных data/identity adapters. `.env.example` — единый шаблон; `bootRun` сам по себе `.env` не загружает, переменные передаются окружением процесса. Общую demo-БД не сбрасывать. Остановить только D1 dependencies без потери данных: `docker compose -p hackalem-d1 -f scripts/d1-compose.yml down`.

## OPS-01: изолированные worktree и тестовый Compose

Базовый проект `hackalem` сохраняет порты 5432/6379/8080/5173 и DNS-имена
`db`, `redis`, `backend`, `frontend`. Имена контейнеров больше не фиксированы.
Все host-порты параметризованы, volume `pgdata` и `attachment-files` получают
имя проекта. Backend ждёт healthy DB и Redis, frontend ждёт healthy backend.
Redis нужен также при host `bootRun`, поэтому входит в запуск зависимостей.
Backend пока совмещает API и chat worker (`CHAT_WORKER_ENABLED`); отдельный
worker service не требуется. Файловый volume смонтирован в
`/var/lib/hackalem/attachments` для будущего storage adapter.

Два независимых offline проекта рядом с общим demo:

```bash
sh scripts/isolated-compose.sh hackalem-ui-test1 1 config
sh scripts/isolated-compose.sh hackalem-ui-test2 2 config
sh scripts/isolated-compose.sh hackalem-ui-test1 1 deps  # только DB + Redis
sh scripts/isolated-compose.sh hackalem-ui-test2 2 deps
sh scripts/isolated-compose.sh hackalem-ui-test1 1 up
sh scripts/isolated-compose.sh hackalem-ui-test2 2 up
sh scripts/isolated-compose.sh hackalem-ui-test1 1 ps
# Остановить только свой проект без удаления его данных:
sh scripts/isolated-compose.sh hackalem-ui-test1 1 down
```

Слоты 1/2 используют DB 55441/55442, Redis 56381/56382, API 18091/18092,
UI 15191/15192. Слоты 1…9 доступны для других worktree/CI. Скрипт запрещает
имя `hackalem` и никогда не выполняет `down -v`. Тестовый профиль использует
backend `test` (offline LLM, no Spring AI chat/embedding auto-config), sample
catalog/stock/cart. Test override принудительно отключает partner cart token и
verified flag; включение live OpenAI не разрешает partner writes.

При host `bootRun` Compose **не экспортирует** `.env` в shell. Передайте нужные
переменные процессу отдельно, например `SPRING_PROFILES_ACTIVE=contract
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:55441/hackalem REDIS_PORT=56381
PORT=18091 ./gradlew bootRun`. Для full/test UI `VITE_API_URL` передаётся только
как Docker build arg, с адресом API из браузера (`localhost:<host-port>`), без
ключей. После изменения адреса frontend нужно пересобрать. Настройки адаптеров
`LLM_ADAPTER_MODE`, `CATALOG_STOCK_ADAPTER_MODE`, `CART_MODE` раздельны в env и
application; существующий backend пока выбирает LLM по Spring profile, а
catalog/stock adapter будет подключён D2. `CART_MODE=sample` и
`PARTNER_CART_VERIFIED=false` остаются безопасными defaults.

## Что реализовано в D1

- Visitor JWT/session ACL, durable conversations/history/runs и idempotent submit.
- Ограниченный agent/tool loop, typed events, SSE replay и versioned dialogue/result sets.
- Immutable proposals, отдельное подтверждение, atomic sample cart и reconciliation неизвестного исхода.
- PostgreSQL V3 baseline, Redis budgets, метрики и [planning load profiles](docs/performance/workload.md).

Локально проходят **43 backend-теста**, HTTP smoke, frontend build/lint. Статус полной приёмки — **in_progress**: [evidence и ограничения](docs/api/d1-handoff.md#evidence). Real D2 data/RAG/parsers, partner identity/cart, полный widget, live OpenAI и high-load не считаются проверенными на основании backend-кода.

## Документация для агентов
- [AGENTS.md](AGENTS.md) — правила монорепо, контракт docker compose, env, troubleshooting
- [backend/AGENTS.md](backend/AGENTS.md) — Spring Boot API
- [frontend/AGENTS.md](frontend/AGENTS.md) — React SPA

## План продукта ekt.kz

- [Единое ТЗ и архитектура](docs/ekt-assistant-spec.md) — каталог, RAG, chat agent, подтверждаемая корзина, вложения и требования 1–5 тыс. суммарных API RPS.
- [Задачи для трёх разработчиков](tasks/README.md) — 32 подробные карточки по функциональным папкам, порядок параллельной работы и приёмка через Docker Compose.
- [Исходное ТЗ партнёра](docs/ТЗ_ИИ-ассистент_ekt.kz.md) — исходные требования, сохранённые как источник.

Это план реализации. Готовность продукта и производительность подтверждаются будущими функциональными и нагрузочными проверками.
