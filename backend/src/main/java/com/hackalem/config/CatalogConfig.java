package com.hackalem.config;

import com.hackalem.ai.catalog.CatalogEmbeddingProvider;
import com.hackalem.ai.catalog.DeterministicCatalogEmbeddingProvider;
import com.hackalem.ai.catalog.OpenAiCatalogEmbeddingProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/** Провайдер векторов и очередь заданий импорта. */
@Configuration
@EnableConfigurationProperties(CatalogProperties.class)
public class CatalogConfig {

    private static final Logger log = LoggerFactory.getLogger(CatalogConfig.class);

    /** Ширина колонки vector(1536) задана миграцией V2/V3 — другая размерность требует новой миграции. */
    private static final int COLUMN_DIMENSIONS = 1536;

    @Bean
    public CatalogEmbeddingProvider catalogEmbeddingProvider(
            CatalogProperties properties,
            ObjectProvider<org.springframework.ai.embedding.EmbeddingModel> embeddingModel,
            @Value("${spring.ai.openai.embedding.options.model:text-embedding-3-small}") String modelName) {

        int dimensions = properties.embedding().dimensions();
        if (dimensions != COLUMN_DIMENSIONS) {
            throw new IllegalStateException("app.catalog.embedding.dimensions=" + dimensions
                    + " не совпадает с колонкой vector(" + COLUMN_DIMENSIONS + "): нужна новая миграция и переиндексация");
        }

        if (properties.embedding().fake()) {
            log.warn("Каталог индексируется детерминированными векторами (CATALOG_EMBEDDING_MODE=fake). "
                    + "Качество семантического поиска в этом режиме ничего не доказывает.");
            return new DeterministicCatalogEmbeddingProvider(dimensions);
        }
        return new OpenAiCatalogEmbeddingProvider(embeddingModel.getObject(), modelName, dimensions,
                properties.embedding().batchSize());
    }

    /**
     * Импорт выполняется одним воркером: параллельная публикация версий никому
     * не нужна, а очередь ограничена, чтобы админский endpoint не копил задания.
     */
    @Bean("catalogImportExecutor")
    public TaskExecutor catalogImportExecutor(CatalogProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(properties.importQueueCapacity());
        executor.setThreadNamePrefix("catalog-import-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
        return executor;
    }
}
