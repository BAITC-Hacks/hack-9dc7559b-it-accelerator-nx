# Backend — Spring Boot API

Java 21, Spring Boot 3.x, Gradle (Groovy DSL). Пакет: `com.hackalem` (ЗАМЕНИ при необходимости).

## Команды
- Запуск:        `./gradlew bootRun`   (порт 8080, БД берётся из docker compose)
- Тесты:         `./gradlew test`
- Сборка:        `./gradlew build`
- Один тест:     `./gradlew test --tests "com.hackalem.*SomeTest"`
- Swagger UI:    http://localhost:8080/swagger-ui.html
- OpenAPI JSON:  http://localhost:8080/v3/api-docs
- Health:        http://localhost:8080/actuator/health

**Перед `bootRun` всегда:** `docker compose up -d` из корня (иначе Flyway упадёт на старте — БД нет).

## Docker / Compose (важно при написании кода)
Backend работает в двух режимах, и код обязан поддерживать оба:

| Режим | Как запускается | Хост БД |
|---|---|---|
| dev (быстрая итерация) | `./gradlew bootRun` с хоста | `localhost:5432` |
| контейнер (демо/деплой) | `docker compose --profile full up -d --build` | `db:5432` |

Правила:
- **Вся конфигурация — через env с дефолтом под dev.** В `application.yml`:
  ```yaml
  spring:
    datasource:
      url: ${SPRING_DATASOURCE_URL:jdbc:postgresql://localhost:5432/hackalem}
      username: ${SPRING_DATASOURCE_USERNAME:hackalem}
      password: ${SPRING_DATASOURCE_PASSWORD:hackalem}
    ai.openai.api-key: ${OPENAI_API_KEY}
  ```
  Тогда `bootRun` работает без настройки, а compose просто переопределяет `SPRING_DATASOURCE_URL` на `db:5432`.
- **Никаких `localhost` в коде.** Внутри compose-сети `localhost` — это сам контейнер, а не соседний сервис.
- Добавил env-переменную → дописал её в корневой `.env.example` **и** в `environment:` сервиса `backend`.
- Нужен `spring-boot-starter-actuator`: `/actuator/health` используется как healthcheck сервиса в compose, от него зависит старт фронта. Эндпоинт держим публичным в SecurityFilterChain.
- Порт не хардкодим: `server.port: ${PORT:8080}` — Railway пробрасывает свой `PORT`.
- Dockerfile — multi-stage, чтобы образ собирался без локальной Java:
  ```dockerfile
  FROM gradle:8-jdk21 AS build
  WORKDIR /app
  COPY . .
  RUN gradle bootJar --no-daemon
  FROM eclipse-temurin:21-jre
  COPY --from=build /app/build/libs/*.jar /app.jar
  EXPOSE 8080
  ENTRYPOINT ["java","-jar","/app.jar"]
  ```
  `.dockerignore`: `build/`, `.gradle/`, `.git/`, `*.md` — иначе сборка тащит мусор и кеш слоёв всё время инвалидируется.
- Поменял `build.gradle` → пересобирать образ с `--build`, иначе в контейнере старый jar.
- Логи контейнера: `docker compose logs -f backend`. Env внутри: `docker compose exec backend env | grep DATASOURCE`.

## Структура пакетов
- `config/`   — конфигурация (beans, OpenAPI, CORS, Spring AI).
- `security/` — JWT (jjwt), фильтры, SecurityFilterChain.
- `domain/`   — JPA-сущности, репозитории, сервисы.
- `web/`      — REST-контроллеры, DTO, MapStruct-мапперы, @RestControllerAdvice.
- `ai/`       — ChatClient, @Tool-методы (tool calling), опционально RAG (PGVector).

## Слои и конвенции
- Контроллеры принимают/отдают **DTO**, не сущности. Маппинг — через **MapStruct** (`@Mapper(componentModel = "spring")`).
- Валидация входа — `@Valid` + аннотации Jakarta Validation на DTO.
- Ошибки — единый `@RestControllerAdvice` (ProblemDetail / структура `{timestamp, status, error, message}`).
- Lombok на сущностях/DTO. **Порядок annotation processors в build.gradle: сначала lombok, затем mapstruct-processor и lombok-mapstruct-binding** — иначе мапперы не увидят геттеры.
- CORS разрешает origin фронта: `http://localhost:5173` (dev) и адрес контейнера/деплоя — берём из env `CORS_ALLOWED_ORIGINS`.

## Flyway
- Миграции в `src/main/resources/db/migration/`: `V1__init.sql`, `V2__seed.sql`.
- Применённую миграцию НЕ менять — только новая `V{n}__*.sql` (Flyway проверяет checksum и упадёт).
- Ошибся в миграции локально и надо начисто: из корня `docker compose down -v && docker compose up -d` — volume `pgdata` стирается, миграции проезжают заново. На общем окружении — предупреди команду.
- Образ БД — `pgvector/pgvector:pg16`, но расширение включает миграция: первой строкой `V1__init.sql` → `CREATE EXTENSION IF NOT EXISTS vector;`.
- Миграции должны проходить на **чистой** БД: это и есть проверка `down -v` → `up`. Не полагайся на данные, которые руками налил в psql.

## Security (JWT)
- Стейтлес, `SessionCreationPolicy.STATELESS`. Публичные эндпоинты: `/auth/**`, `/v3/api-docs/**`, `/swagger-ui/**`, `/actuator/health`.
- Секрет — из env `JWT_SECRET`. Никаких хардкод-ключей.

## Spring AI — паттерн агента с tool calling
```java
@Component
class AppTools {
  @Tool(description = "Найти сущности по фильтру и вернуть краткую сводку")
  List<Item> search(String query) { /* вызов сервиса */ }
}

@RestController
class AgentController {
  private final ChatClient chat;
  private final AppTools tools;
  AgentController(ChatClient.Builder b, AppTools tools) {
    this.chat = b.build(); this.tools = tools;
  }
  @PostMapping("/agent/ask")
  String ask(@RequestBody @Valid AskRequest req) {
    return chat.prompt().user(req.message()).tools(tools).call().content();
  }
}
```
- API-ключ — из env `OPENAI_API_KEY` (никогда не в коде). В контейнере его прокидывает compose из `.env`; забыл заполнить `.env` → приложение падает на старте, это ожидаемо.
- Инструменты (`@Tool`) — тонкие обёртки над сервисами; описание пиши так, чтобы модель знала, КОГДА звать.

## Generic CRUD рецепт (быстрое добавление сущности)
1. Миграция `V{n}__create_<table>.sql`.
2. Entity в `domain/` + `JpaRepository`.
3. DTO + MapStruct-маппер в `web/`.
4. Контроллер на базе `AbstractCrudController<E, D, ID>` (generic base: list/get/create/update/delete).
5. Проверить, что эндпоинт виден в `/v3/api-docs`, затем на фронте `npm run gen`.

## Definition of Done (backend)
- [ ] `./gradlew build` зелёный.
- [ ] Стартует на чистой БД: `docker compose down -v && docker compose up -d && ./gradlew bootRun`.
- [ ] Эндпоинт задокументирован в OpenAPI и отвечает через Swagger UI.
- [ ] Секреты и хосты только через env; новые переменные — в `.env.example` и `docker-compose.yml`.
