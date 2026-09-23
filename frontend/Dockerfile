# Multi-stage: собираем бандл в node, отдаём статику через nginx.

FROM node:20-alpine AS build
WORKDIR /app

# Отдельный слой под зависимости: правка кода не переустанавливает node_modules.
# npm ci требует package-lock.json — он должен быть закоммичен.
COPY package*.json ./
RUN npm ci

COPY . .

# VITE_* — переменные времени СБОРКИ: значение запекается в бандл.
# Передаётся из docker-compose.yml через build.args.
ARG VITE_API_URL=http://localhost:8080
ENV VITE_API_URL=$VITE_API_URL
RUN npm run build

FROM nginx:alpine
# try_files → index.html, иначе рефреш на /items/42 даёт 404 (ловится на демо мгновенно)
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 80
