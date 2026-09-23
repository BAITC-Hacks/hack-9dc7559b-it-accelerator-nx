package com.hackalem.web.catalog;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Тело импорта каталога; совпадает с data/sample_catalog/products.json.
 *
 * Здесь только структурная проверка Jakarta Validation. Содержательные правила
 * (дубли артикулов, согласованность статуса и количества, шаг партии) проверяет
 * доменный валидатор и возвращает их списком с кодами.
 */
@Schema(description = "Документ импорта каталога")
public record CatalogImportRequest(
        @NotNull @Schema(example = "1") Integer schemaVersion,
        Boolean synthetic,
        @NotBlank @Schema(description = "Версия выгрузки источника", example = "synthetic-v1") String version,
        @NotEmpty @Valid List<ProductRequest> products) {

    @Schema(description = "Товар выгрузки. id — стабильный ID поставщика, не PK нашей БД")
    public record ProductRequest(String id,
                                 @NotBlank String article,
                                 @NotBlank String name,
                                 String brand,
                                 String category,
                                 String unit,
                                 @Schema(description = "Минимальная партия", example = "1") BigDecimal minimum,
                                 @Schema(description = "Шаг количества", example = "0.5") BigDecimal step,
                                 BigDecimal price,
                                 String currency,
                                 Map<String, String> specs,
                                 @Valid List<CertificateRequest> certificates,
                                 String sourceUrl,
                                 String sourceVersion,
                                 Boolean synthetic,
                                 @Valid List<WarehouseRequest> warehouses) {
    }

    @Schema(description = "Остаток на складе; количество обязательно только для точных статусов")
    public record WarehouseRequest(@NotBlank String warehouseId,
                                   BigDecimal availableQuantity,
                                   @NotBlank @Schema(allowableValues = {"IN_STOCK", "OUT_OF_STOCK",
                                           "ON_ORDER", "UNKNOWN"}) String status,
                                   Boolean eligible) {
    }

    public record CertificateRequest(@NotBlank String id,
                                     String url,
                                     String file,
                                     String version,
                                     Boolean synthetic) {
    }
}
