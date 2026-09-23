# Frontend — React + Vite + TS

React, Vite, TypeScript, Tailwind, shadcn/ui, react-router, TanStack Query, axios, Recharts.

## Команды
- Dev:        `npm run dev`      (:5173)
- Сборка:     `npm run build`
- Линт:       `npm run lint`
- Формат:     `npm run format`
- Генерация клиента из OpenAPI: `npm run gen`  (@hey-api/openapi-ts)

**Перед `npm run dev` / `npm run gen` нужен живой backend.** Из корня: `docker compose up -d` (БД) + `cd backend && ./gradlew bootRun`, либо целиком в контейнерах — `docker compose --profile full up -d --build`.

## Docker / Compose (важно при написании кода)
- **Адрес API — только через env, никогда не хардкод.** В `src/lib/api.ts`:
  ```ts
  export const API_URL = import.meta.env.VITE_API_URL ?? 'http://localhost:8080';
  ```
  Типизируй в `src/vite-env.d.ts` (`interface ImportMetaEnv { readonly VITE_API_URL: string }`).
- **Главный подвох: `VITE_*` — переменные времени СБОРКИ, а не рантайма.** В собранном образе они уже «запечены» в бандл. Значит в compose их передаём как `build.args`, а не только через `environment:`:
  ```yaml
  frontend:
    build:
      context: ./frontend
      args:
        VITE_API_URL: ${VITE_API_URL:-http://localhost:8080}
  ```
  и в Dockerfile — `ARG VITE_API_URL` + `ENV VITE_API_URL=$VITE_API_URL` перед `npm run build`. Поменял `VITE_API_URL` → нужен `docker compose up -d --build frontend`, простого рестарта мало.
- **`VITE_API_URL` — это адрес из браузера пользователя, а не из контейнера.** Браузер не знает DNS-имени `backend`, поэтому значение остаётся `http://localhost:8080` даже когда фронт крутится в контейнере. `http://backend:8080` тут не работает — типовая ошибка.
- Vite в контейнере в dev-режиме должен слушать наружу: `npm run dev -- --host 0.0.0.0` (иначе порт проброшен, а ответа нет).
- Dockerfile — multi-stage, сборка → раздача nginx:
  ```dockerfile
  FROM node:20-alpine AS build
  WORKDIR /app
  COPY package*.json ./
  RUN npm ci
  COPY . .
  ARG VITE_API_URL
  ENV VITE_API_URL=$VITE_API_URL
  RUN npm run build
  FROM nginx:alpine
  COPY --from=build /app/dist /usr/share/nginx/html
  ```
  `.dockerignore`: `node_modules`, `dist`, `.git` — обязательно, иначе сборка ползёт минутами.
- SPA-роутинг обеспечивает `frontend/nginx.conf` (`try_files $uri /index.html;`) — без него рефреш на `/items/42` даст 404. Конфиг уже в репозитории, копируется в образ Dockerfile'ом.
- Поменял `package.json` → пересобирай с `--build`, слой `npm ci` иначе берётся из кеша.

## Генерация API-клиента (критично)
- Конфиг в `openapi-ts.config.ts` (input = `http://localhost:8080/v3/api-docs`, output = `src/client`).
- `npm run gen` запускается **с хоста**, поэтому input всегда `localhost:8080` (порт проброшен наружу из compose) — не меняй на `backend:8080`.
- Backend должен быть запущен и прожевать старт. Проверка: `curl -s localhost:8080/v3/api-docs | head -c 200`.
- `src/client/**` — генерируемый код, НЕ редактировать руками. Любая правка API → `npm run gen`.

## Структура
- `src/components/ui/` — shadcn/ui примитивы (добавлять через `npx shadcn@latest add <name>`).
- `src/components/`    — свои компоненты.
- `src/pages/`        — страницы (react-router).
- `src/client/`       — сгенерированный API-клиент (не трогать).
- `src/lib/`          — хелперы, конфиг axios/query, `API_URL`.

## TanStack Query паттерны
- Данные с сервера — только через `useQuery`/`useMutation` поверх сгенерированного клиента.
- `queryKey` — стабильные массивы: `['items', id]`.
- После мутаций — `queryClient.invalidateQueries`.
- Один `QueryClientProvider` в корне.

## UI-правила для демо
- Всегда рендерить состояния loading / error / empty — на демо пустой экран = баг.
- Ошибку сети показывай человекочитаемо: «backend недоступен» лучше, чем бесконечный спиннер (на демо это почти всегда «забыли `docker compose up -d`»).
- Мобилки не приоритет; главное — читаемый desktop-экран для питча.
- Графики — Recharts, минимум конфигурации.
- Быстрее использовать готовый shadcn-компонент, чем верстать с нуля.

## Definition of Done (frontend)
- [ ] `npm run build` зелёный, `npm run lint` без ошибок.
- [ ] Ни одного захардкоженного `http://localhost:8080` вне `src/lib/api.ts`.
- [ ] Работает и в dev (`npm run dev`), и в контейнере (`docker compose --profile full up -d --build`).
- [ ] Демо-путь кликается end-to-end.
- [ ] Используется сгенерированный клиент, не ручные fetch-типы.
