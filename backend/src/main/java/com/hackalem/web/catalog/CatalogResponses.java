package com.hackalem.web.catalog;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.Map;

/**
 * Ответы каталога. Все идентификаторы и числа — строки: bigint не должен
 * терять точность в JavaScript, артикул не должен терять ведущие нули, а
 * количество и цена приходят в BigDecimal без промежуточного double.
 */
public final class CatalogResponses {

    private CatalogResponses() {
    }

    @Schema(description = "Товар активной версии каталога")
    public record ProductResponse(
            @Schema(description = "Стабильный ID товара", example = "1001") String id,
            @Schema(description = "ID поставщика, если он известен", example = "1001") String supplierId,
            @Schema(description = "Артикул как в источнике", example = "000001") String article,
            String name,
            String brand,
            String category,
            @Schema(description = "Единица измерения", example = "pcs") String unit,
            @Schema(description = "Минимальная партия", example = "1") String minimumQuantity,
            @Schema(description = "Шаг количества", example = "0.5") String stepQuantity,
            Map<String, String> specs,
            List<CertificateResponse> certificates,
            String sourceUrl,
            @Schema(description = "Версия данных источника", example = "synthetic-v1") String sourceVersion,
            @Schema(description = "Синтетические данные, а не ассортимент партнёра") boolean synthetic,
            String catalogVersionId,
            OfferResponse offer,
            StockResponse stock,
            @Schema(description = "Близость к запросу; только для поиска") Double score) {
    }

    @Schema(description = "Цена товара. UNKNOWN — цена неизвестна, это не ноль")
    public record OfferResponse(String price,
                                String currency,
                                @Schema(allowableValues = {"KNOWN", "UNKNOWN"}) String status,
                                String sourceVersion,
                                String observedAt) {
    }

    @Schema(description = "Сводный остаток. Склады не суммируются без политики отгрузки")
    public record StockResponse(
            @Schema(allowableValues = {"IN_STOCK", "OUT_OF_STOCK", "ON_ORDER", "UNKNOWN"}) String status,
            @Schema(description = "Доступное количество; null, когда оно неизвестно") String availableQuantity,
            @Schema(description = "Почему количество есть или отсутствует",
                    allowableValues = {"SINGLE_WAREHOUSE", "MULTIPLE_WAREHOUSES_NOT_MERGED",
                            "UNKNOWN", "NO_ELIGIBLE_WAREHOUSE"}) String quantityBasis,
            List<WarehouseResponse> warehouses) {
    }

    @Schema(description = "Остаток на складе; eligible=false — отгрузка с него невозможна")
    public record WarehouseResponse(String warehouseId,
                                    String status,
                                    String availableQuantity,
                                    boolean eligible,
                                    String observedAt) {
    }

    public record CertificateResponse(String id, String url, String version, Boolean synthetic) {
    }

    @Schema(description = "Результат поиска вместе с версией каталога, по которой он получен")
    public record ProductSearchResponse(String catalogVersionId,
                                        String sourceVersion,
                                        String vectorSpace,
                                        int total,
                                        List<ProductResponse> items, String mode, List<String> warnings) {
    }

    @Schema(description = "Состояние задания импорта каталога")
    public record ImportJobResponse(String id,
                                    String jobType,
                                    @Schema(allowableValues = {"PENDING", "RUNNING", "SUCCEEDED", "FAILED"})
                                    String status,
                                    String sourceVersion,
                                    String requestedBy,
                                    String vectorSpace,
                                    String catalogVersionId,
                                    String catalogVersionStatus,
                                    String embeddingStatus,
                                    int totalProducts,
                                    int importedProducts,
                                    int embeddedProducts,
                                    List<ImportIssueResponse> errors,
                                    List<ImportIssueResponse> warnings,
                                    String createdAt,
                                    String startedAt,
                                    String finishedAt) {
    }

    @Schema(description = "Ошибка или замечание импорта со стабильным кодом и путём до поля")
    public record ImportIssueResponse(String code, String path, String message) {
    }
}
