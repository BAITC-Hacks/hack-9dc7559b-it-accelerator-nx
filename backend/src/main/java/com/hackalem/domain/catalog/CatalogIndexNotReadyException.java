package com.hackalem.domain.catalog;

/**
 * Семантический поиск невозможен: активной версии нет, индекс не построен или
 * его векторы принадлежат другому пространству (fake против live).
 * Это честная ошибка, а не пустой результат поиска.
 */
public class CatalogIndexNotReadyException extends RuntimeException {

    private final String code;

    public CatalogIndexNotReadyException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
