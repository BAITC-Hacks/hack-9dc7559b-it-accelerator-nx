# Multi-stage: собираем в образе с Gradle, отдаём тонкий JRE-образ.
# Локальная Java для сборки не нужна.

FROM gradle:8-jdk21 AS build
WORKDIR /app

# Слой с зависимостями кешируется отдельно от исходников:
# правка кода не перекачивает весь Maven Central заново.
COPY build.gradle settings.gradle ./
RUN gradle dependencies --no-daemon || true

COPY src ./src
RUN gradle bootJar --no-daemon -x test

FROM eclipse-temurin:21-jre
WORKDIR /app

# curl нужен для healthcheck сервиса в docker-compose.yml (/actuator/health)
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/*

COPY --from=build /app/build/libs/*.jar /app/app.jar

EXPOSE 8080
# MaxRAMPercentage — чтобы JVM уважала лимит памяти контейнера
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/app.jar"]
