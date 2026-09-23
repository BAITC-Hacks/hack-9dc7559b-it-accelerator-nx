package com.hackalem.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * /v3/api-docs — источник правды для фронта (`npm run gen`).
 * Правишь контракт → перегенерируй клиент.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI hackalemOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("HackAlem AI API")
                        .version("v1")
                        .description("ИИ-ассистент для чата на сайте ekt.kz"))
                .components(new Components().addSecuritySchemes("bearerAuth",
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")));
    }
}
