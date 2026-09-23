package com.hackalem.domain.catalog;

import java.math.BigDecimal;

/**
 * Числа наружу уходят строками, но контракты у денег и количеств разные:
 * цена сохраняет масштаб источника ("1500.00"), количество — нет ("12", "37.5").
 * Экспоненциальная запись не допускается ни там, ни там.
 */
public final class Decimals {

    private Decimals() {
    }

    /** Деньги: масштаб как в источнике. */
    public static String money(BigDecimal value) {
        return value == null ? null : value.toPlainString();
    }

    /** Количество: без хвостовых нулей, но всегда в обычной записи. */
    public static String quantity(BigDecimal value) {
        return value == null ? null : value.stripTrailingZeros().toPlainString();
    }
}
