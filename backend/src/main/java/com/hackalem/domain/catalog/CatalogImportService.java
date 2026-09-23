package com.hackalem.domain.catalog;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hackalem.ai.catalog.CatalogEmbeddingProvider;
import com.hackalem.ai.catalog.ProductEmbeddingText;
import com.hackalem.config.CatalogProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * Импорт каталога: валидация → векторы → запись версии → публикация.
 *
 * Порядок выбран так, чтобы ни один сетевой вызов не выполнялся внутри
 * транзакции БД и чтобы упавший батч не портил активные данные: версия
 * становится ACTIVE только последним шагом, CAS-переключением.
 */
@Service
public class CatalogImportService {

    private static final Logger log = LoggerFactory.getLogger(CatalogImportService.class);

    private final CatalogRepository catalog;
    private final CatalogImportJobRepository jobs;
    private final CatalogImportValidator validator;
    private final CatalogEmbeddingProvider embeddings;
    private final CatalogProperties properties;
    private final ObjectMapper objectMapper;
    private final TaskExecutor executor;

    public CatalogImportService(CatalogRepository catalog,
                                CatalogImportJobRepository jobs,
                                CatalogImportValidator validator,
                                CatalogEmbeddingProvider embeddings,
                                CatalogProperties properties,
                                ObjectMapper objectMapper,
                                @Qualifier("catalogImportExecutor") TaskExecutor executor) {
        this.catalog = catalog;
        this.jobs = jobs;
        this.validator = validator;
        this.embeddings = embeddings;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.executor = executor;
    }

    /**
     * Принимает документ и ставит задание в очередь. Валидация синхронная:
     * заведомо битый файл отклоняется сразу, а не через статус задания.
     */
    public CatalogImportJob submit(CatalogImportDocument document, String idempotencyKey, String requestedBy) {
        return start(document, idempotencyKey, requestedBy, true);
    }

    /**
     * То же самое, но импорт выполняется в вызывающем потоке: стартовый seed
     * должен закончиться до того, как приложение объявит себя готовым.
     */
    public CatalogImportJob importNow(CatalogImportDocument document, String idempotencyKey, String requestedBy) {
        return start(document, idempotencyKey, requestedBy, false);
    }

    private CatalogImportJob start(CatalogImportDocument document, String idempotencyKey, String requestedBy,
                                   boolean async) {
        CatalogImportValidator.Result validation = validator.validate(document);
        if (!validation.valid()) {
            throw new CatalogValidationException(validation.errors());
        }

        String contentHash = contentHash(document);
        CatalogImportJobRepository.Handle handle = jobs.createOrGet(idempotencyKey, contentHash,
                document.version(), requestedBy, embeddings.vectorSpace(), document.products().size());
        if (!handle.created()) {
            log.info("Импорт {} — повтор по ключу идемпотентности, новое задание не создаётся", handle.job().id());
            return handle.job();
        }

        long jobId = handle.job().id();
        if (async) {
            executor.execute(() -> run(jobId, document, contentHash, validation.warnings()));
        } else {
            run(jobId, document, contentHash, validation.warnings());
        }
        return jobs.find(jobId).orElse(handle.job());
    }

    /** Выполняет импорт синхронно — используется тестами и стартовым initializer'ом. */
    public void run(long jobId, CatalogImportDocument document, String contentHash,
                    List<CatalogImportError> initialWarnings) {
        List<CatalogImportError> warnings = new ArrayList<>(initialWarnings);
        Long versionId = null;
        try {
            versionId = catalog.createVersion(document.version(), document.version(),
                    embeddings.vectorSpace(), embeddings.model(), embeddings.dimensions(),
                    contentHash, jobId);
            jobs.markRunning(jobId, versionId);

            List<CatalogImportDocument.Product> products = document.products();
            List<String> texts = products.stream().map(ProductEmbeddingText::of).toList();

            // Вызов провайдера — до открытия транзакции записи.
            Embeddings vectors = embed(texts, warnings);

            List<StagedProduct> staged = new ArrayList<>(products.size());
            for (int i = 0; i < products.size(); i++) {
                float[] vector = vectors.vectors() == null ? null : vectors.vectors().get(i);
                staged.add(new StagedProduct(products.get(i), texts.get(i), vector));
            }

            CatalogRepository.StageResult result = catalog.stage(versionId, staged, document.version());
            warnings.addAll(result.warnings());

            int embedded = vectors.status() == EmbeddingStatus.READY ? result.importedProducts() : 0;
            catalog.markVersionReady(versionId, result.importedProducts(), vectors.status(), embedded);
            catalog.publishVersion(versionId);

            int removed = catalog.deleteSupersededBeyond(properties.retainedVersions());
            if (removed > 0) {
                log.info("Удалено вытесненных версий каталога: {}", removed);
            }

            jobs.markSucceeded(jobId, result.importedProducts(), embedded, warnings);
            log.info("Импорт {} завершён: версия {}, товаров {}, индексация {}",
                    jobId, versionId, result.importedProducts(), vectors.status());
        } catch (Exception ex) {
            log.error("Импорт {} провалился: {}", jobId, ex.getMessage(), ex);
            if (versionId != null) {
                catalog.markVersionFailed(versionId, codeOf(ex), ex.getMessage());
            }
            jobs.markFailed(jobId, List.of(CatalogImportError.of(codeOf(ex), "$",
                    ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage())), warnings);
        }
    }

    /**
     * Ошибка индексации не отменяет импорт: содержимое каталога валидно, поэтому
     * версия публикуется, а семантический поиск честно сообщает, что индекса нет.
     * Точный поиск по артикулу от провайдера не зависит.
     */
    private Embeddings embed(List<String> texts, List<CatalogImportError> warnings) {
        try {
            List<float[]> vectors = embeddings.embed(texts);
            if (vectors.size() != texts.size()) {
                warnings.add(CatalogImportError.of("EMBEDDING_COUNT_MISMATCH", "$.products",
                        "Провайдер вернул " + vectors.size() + " векторов вместо " + texts.size()));
                return new Embeddings(null, EmbeddingStatus.FAILED);
            }
            for (float[] vector : vectors) {
                if (vector.length != embeddings.dimensions()) {
                    warnings.add(CatalogImportError.of("EMBEDDING_DIMENSION_MISMATCH", "$.products",
                            "Провайдер вернул вектор размерности " + vector.length
                                    + " вместо " + embeddings.dimensions()));
                    return new Embeddings(null, EmbeddingStatus.FAILED);
                }
            }
            return new Embeddings(vectors, EmbeddingStatus.READY);
        } catch (Exception ex) {
            log.warn("Индексация каталога не выполнена: {}", ex.getMessage());
            warnings.add(CatalogImportError.of("EMBEDDING_FAILED", "$.products",
                    "Векторы не построены: " + ex.getMessage()));
            return new Embeddings(null, EmbeddingStatus.FAILED);
        }
    }

    /** SHA-256 канонического представления документа: основа идемпотентности seed'а. */
    public String contentHash(CatalogImportDocument document) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] payload = objectMapper.writeValueAsString(document).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(digest.digest(payload));
        } catch (Exception ex) {
            throw new IllegalStateException("Не удалось посчитать хеш документа импорта", ex);
        }
    }

    private static String codeOf(Exception ex) {
        return ex instanceof CatalogConflictException conflict ? conflict.code() : "CATALOG_IMPORT_FAILED";
    }

    private record Embeddings(List<float[]> vectors, EmbeddingStatus status) {
    }
}
