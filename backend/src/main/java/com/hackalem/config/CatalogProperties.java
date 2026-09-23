package com.hackalem.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Настройки каталога. Все значения приходят из env с рабочим дефолтом под dev,
 * см. application.yml, .env.example и docker-compose.yml.
 */
@ConfigurationProperties(prefix = "app.catalog")
public record CatalogProperties(@DefaultValue Embedding embedding,
                                @DefaultValue Seed seed,
                                @DefaultValue("3") int retainedVersions,
                                @DefaultValue("8") int importQueueCapacity) {

    /**
     * Режим векторов: live — реальная модель (нужен ключ), fake — детерминированная
     * заглушка для offline и тестов. Пространства векторов не смешиваются.
     */
    public record Embedding(@DefaultValue("live") String mode,
                            @DefaultValue("64") int batchSize,
                            @DefaultValue("1536") int dimensions) {

        public boolean fake() {
            return "fake".equalsIgnoreCase(mode);
        }
    }

    /** Загрузка синтетического каталога при старте; повторный запуск идемпотентен. */
    public record Seed(@DefaultValue("true") boolean enabled,
                       @DefaultValue("file:../data/sample_catalog/products.json") String location) {
    }
}
