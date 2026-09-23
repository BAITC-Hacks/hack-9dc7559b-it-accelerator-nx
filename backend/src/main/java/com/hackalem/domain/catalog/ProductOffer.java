package com.hackalem.domain.catalog;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Цена товара в активной версии каталога. Цены может не быть (legacy-строка
 * без price) — тогда это явное «неизвестно», а не ноль.
 */
public record ProductOffer(BigDecimal price, String currency, String sourceVersion, Instant observedAt) {

    public boolean known() {
        return price != null;
    }
}
