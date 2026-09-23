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
ARG VITE_EMBED_ALLOWED_ORIGINS=http://localhost:5180,http://localhost:5173
ENV VITE_EMBED_ALLOWED_ORIGINS=$VITE_EMBED_ALLOWED_ORIGINS
RUN npm run build

FROM nginx:alpine
# Official image runs envsubst on /etc/nginx/templates/*.template
ENV EMBED_FRAME_ANCESTORS="'self' http://localhost:5180 http://localhost:5173"
COPY nginx.conf /etc/nginx/templates/default.conf.template
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 80
