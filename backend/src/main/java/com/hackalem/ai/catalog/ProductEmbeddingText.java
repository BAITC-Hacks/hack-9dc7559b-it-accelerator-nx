package com.hackalem.ai.catalog;

import com.hackalem.domain.catalog.CatalogImportDocument;
import com.hackalem.domain.catalog.Decimals;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Текст, по которому строится вектор товара. Порядок частей и характеристик
 * фиксирован: одинаковый товар должен давать одинаковый текст при повторном
 * импорте, иначе идемпотентность индексации теряется.
 */
public final class ProductEmbeddingText {

    private ProductEmbeddingText() {
    }

    public static String of(CatalogImportDocument.Product product) {
        List<String> parts = new ArrayList<>();
        addIfPresent(parts, product.name());
        addIfPresent(parts, product.brand());
        addIfPresent(parts, product.category());
        addIfPresent(parts, product.article());
        if (product.unit() != null && product.minimum() != null) {
            parts.add("единица " + product.unit() + ", минимум " + Decimals.quantity(product.minimum()));
        }
        Map<String, String> specs = product.specs() == null ? Map.of() : new TreeMap<>(product.specs());
        specs.forEach((key, value) -> parts.add(key + ": " + value));
        return String.join(". ", parts);
    }

    private static void addIfPresent(List<String> parts, String value) {
        if (value != null && !value.isBlank()) {
            parts.add(value.strip());
        }
    }
}
