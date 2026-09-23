package com.hackalem.domain.catalog;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;

/**
 * Сводный остаток товара по складам.
 *
 * Правила сведения намеренно консервативные: неизвестность одного доступного
 * склада делает неизвестным весь остаток, а несколько складов не суммируются
 * без политики fulfillment. Политику вводит CAT-03, а не эта задача.
 */
public record ProductStock(StockStatus status,
                           BigDecimal availableQuantity,
                           QuantityBasis quantityBasis,
                           List<WarehouseAvailability> warehouses) {

    public static ProductStock from(List<WarehouseAvailability> rows) {
        List<WarehouseAvailability> all = rows == null ? List.of() : rows.stream()
                .sorted(Comparator.comparing(WarehouseAvailability::warehouseId))
                .toList();
        List<WarehouseAvailability> eligible = all.stream()
                .filter(WarehouseAvailability::eligible)
                .toList();

        if (eligible.isEmpty()) {
            return new ProductStock(StockStatus.UNKNOWN, null, QuantityBasis.NO_ELIGIBLE_WAREHOUSE, all);
        }
        if (eligible.stream().anyMatch(w -> w.status() == StockStatus.UNKNOWN)) {
            return new ProductStock(StockStatus.UNKNOWN, null, QuantityBasis.UNKNOWN, all);
        }

        List<WarehouseAvailability> inStock = eligible.stream()
                .filter(w -> w.status() == StockStatus.IN_STOCK)
                .toList();
        if (inStock.size() == 1) {
            return new ProductStock(StockStatus.IN_STOCK, inStock.get(0).availableQuantity(),
                    QuantityBasis.SINGLE_WAREHOUSE, all);
        }
        if (inStock.size() > 1) {
            return new ProductStock(StockStatus.IN_STOCK, null,
                    QuantityBasis.MULTIPLE_WAREHOUSES_NOT_MERGED, all);
        }
        if (eligible.stream().anyMatch(w -> w.status() == StockStatus.ON_ORDER)) {
            return new ProductStock(StockStatus.ON_ORDER, null, QuantityBasis.UNKNOWN, all);
        }
        // Остались только точные нули: это именно ноль, а не «не удалось проверить».
        BigDecimal zero = eligible.size() == 1 ? eligible.get(0).availableQuantity() : null;
        QuantityBasis basis = eligible.size() == 1
                ? QuantityBasis.SINGLE_WAREHOUSE
                : QuantityBasis.MULTIPLE_WAREHOUSES_NOT_MERGED;
        return new ProductStock(StockStatus.OUT_OF_STOCK, zero, basis, all);
    }
}
