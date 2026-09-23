package com.hackalem.domain.catalog;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Входной документ импорта каталога. Форма совпадает с
 * data/sample_catalog/products.json (DATA-01), поэтому один и тот же файл
 * принимается и HTTP-импортом, и стартовым initializer'ом без второго парсера.
 *
 * Числа приходят строками и читаются в BigDecimal: так не теряются ведущие нули
 * артикула и точность количеств. {@code id} — стабильный ID поставщика, а не PK
 * нашей БД: собственный id товара выдаёт последовательность и он не меняется.
 */
public record CatalogImportDocument(Integer schemaVersion,
                                    Boolean synthetic,
                                    String version,
                                    List<Product> products) {

    public static final int SUPPORTED_SCHEMA_VERSION = 1;

    public record Product(String id,
                          String article,
                          String name,
                          String brand,
                          String category,
                          String unit,
                          BigDecimal minimum,
                          BigDecimal step,
                          BigDecimal price,
                          String currency,
                          Map<String, String> specs,
                          List<ProductCertificate> certificates,
                          String sourceUrl,
                          String sourceVersion,
                          Boolean synthetic,
                          List<Warehouse> warehouses) {
    }

    public record Warehouse(String warehouseId,
                            BigDecimal availableQuantity,
                            String status,
                            Boolean eligible) {
    }
}
