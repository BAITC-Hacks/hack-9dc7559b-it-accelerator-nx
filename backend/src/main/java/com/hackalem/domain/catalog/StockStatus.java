package com.hackalem.domain.catalog;

/**
 * Статус остатка. Количество известно только для IN_STOCK и OUT_OF_STOCK:
 * UNKNOWN и ON_ORDER количества не имеют, и выдумывать 0/1 нельзя.
 * То же правило закреплено ограничением warehouse_stock_quantity_chk в V3.
 */
public enum StockStatus {
    IN_STOCK,
    OUT_OF_STOCK,
    ON_ORDER,
    UNKNOWN;

    public boolean hasKnownQuantity() {
        return this == IN_STOCK || this == OUT_OF_STOCK;
    }
}
