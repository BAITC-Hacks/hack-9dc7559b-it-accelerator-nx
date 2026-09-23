package com.hackalem.domain.catalog;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Товар из активной версии каталога вместе с ценой и остатками.
 * {@code score} заполняется только семантическим поиском.
 */
public record CatalogProduct(long id,
                             String supplierId,
                             String article,
                             String articleNormalized,
                             String name,
                             String brand,
                             String category,
                             String unit,
                             BigDecimal minimumQuantity,
                             BigDecimal stepQuantity,
                             Map<String, String> specs,
                             List<ProductCertificate> certificates,
                             String sourceUrl,
                             String sourceVersion,
                             boolean synthetic,
                             long catalogVersionId,
                             ProductOffer offer,
                             ProductStock stock,
                             Double score) {
}
