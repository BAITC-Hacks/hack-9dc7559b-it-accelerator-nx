package com.hackalem.catalog;

import com.hackalem.domain.catalog.ProductStock;
import com.hackalem.domain.catalog.QuantityBasis;
import com.hackalem.domain.catalog.StockStatus;
import com.hackalem.domain.catalog.WarehouseAvailability;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ProductStockTest {

    private static WarehouseAvailability warehouse(String id, StockStatus status, String quantity,
                                                   boolean eligible) {
        return new WarehouseAvailability(id, status,
                quantity == null ? null : new BigDecimal(quantity), eligible, Instant.EPOCH);
    }

    @Test
    @DisplayName("Недоступный склад не добавляет остаток: у 1001 доступно 12, а не 1011")
    void ignoresIneligibleWarehouse() {
        ProductStock stock = ProductStock.from(List.of(
                warehouse("ALA", StockStatus.IN_STOCK, "12", true),
                warehouse("CLOSED", StockStatus.IN_STOCK, "999", false)));

        assertThat(stock.status()).isEqualTo(StockStatus.IN_STOCK);
        assertThat(stock.availableQuantity()).isEqualByComparingTo("12");
        assertThat(stock.quantityBasis()).isEqualTo(QuantityBasis.SINGLE_WAREHOUSE);
        assertThat(stock.warehouses()).hasSize(2);
    }

    @Test
    @DisplayName("Неизвестный остаток остаётся неизвестным, а не нулём")
    void unknownStaysUnknown() {
        ProductStock stock = ProductStock.from(List.of(
                warehouse("ALA", StockStatus.UNKNOWN, null, true)));

        assertThat(stock.status()).isEqualTo(StockStatus.UNKNOWN);
        assertThat(stock.availableQuantity()).isNull();
    }

    @Test
    @DisplayName("Ноль — это точный ноль")
    void zeroIsExact() {
        ProductStock stock = ProductStock.from(List.of(
                warehouse("ALA", StockStatus.OUT_OF_STOCK, "0", true)));

        assertThat(stock.status()).isEqualTo(StockStatus.OUT_OF_STOCK);
        assertThat(stock.availableQuantity()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("Несколько доступных складов не суммируются без политики отгрузки")
    void doesNotMergeWarehouses() {
        ProductStock stock = ProductStock.from(List.of(
                warehouse("ALA", StockStatus.IN_STOCK, "12", true),
                warehouse("AST", StockStatus.IN_STOCK, "8", true)));

        assertThat(stock.status()).isEqualTo(StockStatus.IN_STOCK);
        assertThat(stock.availableQuantity()).isNull();
        assertThat(stock.quantityBasis()).isEqualTo(QuantityBasis.MULTIPLE_WAREHOUSES_NOT_MERGED);
    }

    @Test
    @DisplayName("Ни одного доступного склада — остаток неизвестен, а не нулевой")
    void noEligibleWarehouse() {
        ProductStock stock = ProductStock.from(List.of(
                warehouse("CLOSED", StockStatus.IN_STOCK, "999", false)));

        assertThat(stock.status()).isEqualTo(StockStatus.UNKNOWN);
        assertThat(stock.availableQuantity()).isNull();
        assertThat(stock.quantityBasis()).isEqualTo(QuantityBasis.NO_ELIGIBLE_WAREHOUSE);
    }
}
