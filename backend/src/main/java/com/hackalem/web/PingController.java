package com.hackalem.web;

import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/**
 * Единственный эндпоинт каркаса: проверка связки фронт → API и наличие хотя бы
 * одной операции в /v3/api-docs, чтобы работал `npm run gen`.
 * Появятся реальные контроллеры — этот можно удалить.
 */
@RestController
@RequestMapping("/api/ping")
@Tag(name = "Ping", description = "Проверка живости API")
public class PingController {

    private final String appName;

    public PingController(@Value("${spring.application.name}") String appName) {
        this.appName = appName;
    }

    public record PingResponse(String app, String status, Instant time) {
    }

    @GetMapping
    public PingResponse ping() {
        return new PingResponse(appName, "ok", Instant.now());
    }
}
