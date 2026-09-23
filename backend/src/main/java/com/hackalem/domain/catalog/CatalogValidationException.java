package com.hackalem.domain.catalog;

import java.util.List;

/** Документ импорта не прошёл проверку. Ни одна строка каталога не изменена. */
public class CatalogValidationException extends RuntimeException {

    private final transient List<CatalogImportError> errors;

    public CatalogValidationException(List<CatalogImportError> errors) {
        super("Импорт каталога отклонён: " + errors.size() + " ошибок валидации");
        this.errors = List.copyOf(errors);
    }

    public List<CatalogImportError> errors() {
        return errors;
    }
}
