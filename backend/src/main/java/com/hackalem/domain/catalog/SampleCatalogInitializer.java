package com.hackalem.domain.catalog;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hackalem.ai.catalog.CatalogEmbeddingProvider;
import com.hackalem.config.CatalogProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.Optional;

/**
 * Загрузка синтетического каталога при старте. Ручных SQL-шагов для демо нет:
 * чистый `docker compose up` даёт готовый каталог.
 *
 * Идемпотентность — по хешу содержимого и пространству векторов активной версии:
 * рестарт контейнера не создаёт вторую версию и не дублирует товары. Если файла
 * нет (продовый запуск на данных партнёра) — это не ошибка старта.
 */
@Component
public class SampleCatalogInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SampleCatalogInitializer.class);

    private final CatalogProperties properties;
    private final CatalogRepository catalog;
    private final CatalogImportService importService;
    private final CatalogEmbeddingProvider embeddings;
    private final ResourceLoader resourceLoader;
    private final ObjectMapper objectMapper;

    public SampleCatalogInitializer(CatalogProperties properties,
                                    CatalogRepository catalog,
                                    CatalogImportService importService,
                                    CatalogEmbeddingProvider embeddings,
                                    ResourceLoader resourceLoader,
                                    ObjectMapper objectMapper) {
        this.properties = properties;
        this.catalog = catalog;
        this.importService = importService;
        this.embeddings = embeddings;
        this.resourceLoader = resourceLoader;
        this.objectMapper = objectMapper;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.seed().enabled()) {
            log.info("Стартовая загрузка каталога отключена (CATALOG_SEED_ENABLED=false)");
            return;
        }
        String location = properties.seed().location();
        try {
            Resource resource = resourceLoader.getResource(location);
            if (!resource.exists()) {
                log.warn("Файл каталога {} не найден: стартовая загрузка пропущена. "
                        + "В compose он монтируется в /app/data (CATALOG_SEED_LOCATION).", location);
                return;
            }
            CatalogImportDocument document;
            try (InputStream input = resource.getInputStream()) {
                document = objectMapper.readValue(input, CatalogImportDocument.class);
            }
            String hash = importService.contentHash(document);
            if (alreadySeeded(hash)) {
                log.info("Каталог '{}' уже загружен в пространстве {}: повторный импорт не нужен",
                        document.version(), embeddings.vectorSpace());
                return;
            }
            CatalogImportJob job = importService.importNow(document, null, "initializer");
            log.info("Стартовая загрузка каталога: задание {} — {}", job.id(), job.status());
        } catch (Exception ex) {
            // Старт приложения не срывается: каталог просто останется прежним.
            log.error("Стартовая загрузка каталога из {} не выполнена: {}", location, ex.getMessage(), ex);
        }
    }

    private boolean alreadySeeded(String contentHash) {
        Optional<CatalogVersion> active = catalog.findActiveVersion();
        return active.filter(version -> contentHash.equals(version.contentHash())
                && embeddings.vectorSpace().equals(version.vectorSpace())
                && version.embeddingStatus() == EmbeddingStatus.READY).isPresent();
    }
}
