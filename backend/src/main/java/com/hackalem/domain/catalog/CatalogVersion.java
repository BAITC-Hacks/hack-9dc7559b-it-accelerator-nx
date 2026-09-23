package com.hackalem.domain.catalog;

import java.time.Instant;

/** Версия каталога: содержимое и состояние её индекса. */
public record CatalogVersion(long id,
                             String label,
                             String sourceVersion,
                             CatalogVersionStatus status,
                             EmbeddingStatus embeddingStatus,
                             String embeddingModel,
                             Integer embeddingDimensions,
                             String vectorSpace,
                             String contentHash,
                             int productCount,
                             int embeddedCount,
                             Long importJobId,
                             Instant createdAt,
                             Instant publishedAt) {
}
