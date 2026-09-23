# Host-demo — отдельный origin для проверки embed (UI-05)

Статическая страница на порту **5180**. Не является вторым frontend codebase.

```bash
# вместе с frontend (profile full)
docker compose --profile full up -d --build

# или только статикой локально
npx --yes serve host-demo -p 5180
```

Открой http://localhost:5180 — лаунчер подгружает
`http://localhost:5173/embed/v1/widget.js` и создаёт iframe `/widget`.
