@AGENTS.md

## Claude Code — frontend
- Не редактируй `src/client/**` — регенерируй через `npm run gen` (нужен живой backend на :8080).
- Адрес API берётся только из `VITE_API_URL` через `src/lib/api.ts`; хардкод `localhost:8080` по компонентам — ошибка.
- Помни: `VITE_*` — build-time. Менял значение → `docker compose up -d --build frontend`, рестарта недостаточно.
- `VITE_API_URL` — адрес для браузера, а не для compose-сети: `http://backend:8080` там не работает.
- Всегда добавляй состояния loading/error/empty; ошибку сети показывай текстом, а не вечным спиннером.
- Предпочитай существующие shadcn/ui компоненты.
