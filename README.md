# hack-9dc7559b-it-accelerator-nx

Hackathon team repository for TechnoHorizon.
Кейс: **ИИ-ассистент для чата на сайте ekt.kz**.

Стек: Spring Boot 3.5 (Java 21, Gradle) + PostgreSQL/pgvector + Flyway + Spring AI ·
React 19 + Vite + TypeScript + Tailwind + TanStack Query.

## Быстрый старт

```bash
cp .env.example .env     # заполнить OPENAI_API_KEY
docker compose up -d     # Postgres + pgvector
```

Dev-режим (hot reload):

```bash
cd backend && ./gradlew bootRun     # http://localhost:8080  (Swagger: /swagger-ui.html)
cd frontend && npm install && npm run dev   # http://localhost:5173
```

Всё в контейнерах (проверка перед демо):

```bash
docker compose --profile full up -d --build
```

Полный ресет БД: `docker compose down -v && docker compose up -d`

## Что уже есть в каркасе

**backend/** — Spring Boot приложение, стартует на пустой БД:
- `config/OpenApiConfig` — springdoc, `/v3/api-docs` + `/swagger-ui.html`, схема авторизации `bearerAuth`;
- `security/SecurityConfig` — stateless, CORS из `CORS_ALLOWED_ORIGINS`, `PasswordEncoder`; пока всё открыто, место под JWT-фильтр помечено комментарием;
- `web/PingController` — `GET /api/ping`, чтобы проверять связку и чтобы `npm run gen` имел хотя бы одну операцию;
- `db/migration/V1__init.sql` включает `vector`; `V2__products_vector_search.sql` создаёт `products` и HNSW-индекс;
- `web/ProductSearchController` / `ProductSearchService` — начальный семантический поиск и upsert продуктов; количественные остатки, чат и корзина ещё требуют реализации;
- пакеты `domain/`, `web/`, `ai/` под слои из [backend/AGENTS.md](backend/AGENTS.md);
- зависимости уже подключены: JPA, Flyway, Validation, Actuator, Security, springdoc, Spring AI (OpenAI), jjwt, Lombok + MapStruct (в правильном порядке процессоров).

**frontend/** — Vite SPA, собирается и линтуется:
- `src/lib/api.ts` — единственное место с адресом API (`VITE_API_URL`), axios-инстанс, хранилище токена;
- `src/lib/query.ts` — общий `QueryClient`;
- `src/client/` — **сгенерированный** клиент (`npm run gen`), руками не править;
- `src/pages/HomePage.tsx` — страница-пример: дергает `/api/ping` через сгенерированный клиент, рендерит loading / error / ok;
- Tailwind v4 + токены shadcn/ui и `components.json` — `npx shadcn@latest add button` работает сразу;
- ESLint + Prettier настроены (`npm run lint`, `npm run format`).

Демо-пользователей, истории чата и корзины пока нет; начальная таблица товаров уже добавлена.

## Проверка, что всё живо

```bash
curl -s localhost:8080/actuator/health   # {"status":"UP"}
curl -s localhost:8080/api/ping          # {"app":"hackalem-backend","status":"ok",...}
curl -s localhost:8080/v3/api-docs | head -c 200
```

Открыть http://localhost:5173 — на странице должен быть блок «Состояние API» со `status: ok`.

## Документация для агентов
- [AGENTS.md](AGENTS.md) — правила монорепо, контракт docker compose, env, troubleshooting
- [backend/AGENTS.md](backend/AGENTS.md) — Spring Boot API
- [frontend/AGENTS.md](frontend/AGENTS.md) — React SPA

## План продукта ekt.kz

- [Единое ТЗ и архитектура](docs/ekt-assistant-spec.md) — каталог, RAG, chat agent, подтверждаемая корзина, вложения и требования 1–5 тыс. суммарных API RPS.
- [Задачи для трёх разработчиков](tasks/README.md) — 32 подробные карточки по функциональным папкам, порядок параллельной работы и приёмка через Docker Compose.
- [Исходное ТЗ партнёра](docs/ТЗ_ИИ-ассистент_ekt.kz.md) — исходные требования, сохранённые как источник.

Это план реализации. Готовность продукта и производительность подтверждаются будущими функциональными и нагрузочными проверками.
