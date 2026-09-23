package com.hackalem.domain.catalog;

import java.time.Instant;
import java.util.List;

/** Задание импорта каталога — то, что отдаёт GET /api/admin/jobs/{id}. */
public record CatalogImportJob(long id,
                               String jobType,
                               ImportJobStatus status,
                               String idempotencyKey,
                               String contentHash,
                               String sourceVersion,
                               String requestedBy,
                               String vectorSpace,
                               Long catalogVersionId,
                               CatalogVersionStatus catalogVersionStatus,
                               EmbeddingStatus embeddingStatus,
                               int totalProducts,
                               int importedProducts,
                               int embeddedProducts,
                               List<CatalogImportError> errors,
                               List<CatalogImportError> warnings,
                               Instant createdAt,
                               Instant startedAt,
                               Instant finishedAt) {
}
