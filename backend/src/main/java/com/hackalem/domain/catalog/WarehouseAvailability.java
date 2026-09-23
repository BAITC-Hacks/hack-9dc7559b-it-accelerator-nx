package com.hackalem.domain.catalog;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Остаток на конкретном складе. {@code availableQuantity} равен null, когда
 * количество неизвестно; {@code eligible} = false означает склад, с которого
 * отгрузка невозможна — его остаток нельзя прибавлять к доступному.
 */
public record WarehouseAvailability(String warehouseId,
                                    StockStatus status,
                                    BigDecimal availableQuantity,
                                    boolean eligible,
                                    Instant observedAt) {
}
