package com.hackalem.domain.catalog;

/** Конкурентная публикация версии каталога: CAS не прошёл, активные данные целы. */
public class CatalogConflictException extends RuntimeException {

    private final String code;

    public CatalogConflictException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
