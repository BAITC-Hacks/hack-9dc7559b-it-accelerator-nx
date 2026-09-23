package com.hackalem.domain.catalog;

/**
 * Ошибка или предупреждение импорта: стабильный код, путь до поля и сообщение.
 * Коды стабильны — на них можно ссылаться в тестах и в отчётах приёмки.
 */
public record CatalogImportError(String code, String path, String message) {

    public static CatalogImportError of(String code, String path, String message) {
        return new CatalogImportError(code, path, message);
    }
}
