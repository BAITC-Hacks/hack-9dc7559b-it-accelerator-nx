package com.hackalem.domain.catalog;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Проверка документа импорта до любой записи в БД и до вызова провайдера.
 *
 * Ошибки возвращаются списком с кодом и путём до поля: непонятное «что-то не так»
 * на импорте каталога стоит дороже, чем несколько строк проверок. Предупреждения
 * не блокируют импорт, но сохраняются в задании.
 */
@Component
public class CatalogImportValidator {

    private static final Pattern SUPPLIER_ID = Pattern.compile("^[0-9A-Za-z._-]{1,64}$");
    private static final int MAX_ARTICLE_LENGTH = 255;

    public Result validate(CatalogImportDocument document) {
        List<CatalogImportError> errors = new ArrayList<>();
        List<CatalogImportError> warnings = new ArrayList<>();

        if (document == null) {
            errors.add(CatalogImportError.of("EMPTY_IMPORT", "$", "Документ импорта отсутствует"));
            return new Result(errors, warnings);
        }
        if (document.schemaVersion() == null
                || document.schemaVersion() != CatalogImportDocument.SUPPORTED_SCHEMA_VERSION) {
            errors.add(CatalogImportError.of("UNSUPPORTED_SCHEMA_VERSION", "$.schemaVersion",
                    "Поддерживается только schemaVersion=" + CatalogImportDocument.SUPPORTED_SCHEMA_VERSION));
        }
        if (isBlank(document.version())) {
            errors.add(CatalogImportError.of("MISSING_SOURCE_VERSION", "$.version",
                    "Версия источника обязательна: без неё нельзя отличить данные разных выгрузок"));
        }
        List<CatalogImportDocument.Product> products = document.products();
        if (products == null || products.isEmpty()) {
            errors.add(CatalogImportError.of("EMPTY_IMPORT", "$.products", "Список товаров пуст"));
            return new Result(errors, warnings);
        }

        Map<String, Integer> articleSeen = new HashMap<>();
        Map<String, Integer> supplierSeen = new HashMap<>();
        for (int i = 0; i < products.size(); i++) {
            validateProduct(products.get(i), "$.products[" + i + "]", i, articleSeen, supplierSeen,
                    errors, warnings);
        }
        return new Result(errors, warnings);
    }

    private void validateProduct(CatalogImportDocument.Product product, String path, int index,
                                 Map<String, Integer> articleSeen, Map<String, Integer> supplierSeen,
                                 List<CatalogImportError> errors, List<CatalogImportError> warnings) {
        if (product == null) {
            errors.add(CatalogImportError.of("EMPTY_PRODUCT", path, "Пустой элемент списка товаров"));
            return;
        }

        String normalized = ArticleNormalizer.normalize(product.article());
        if (normalized == null) {
            errors.add(CatalogImportError.of("MISSING_ARTICLE", path + ".article", "Артикул обязателен"));
        } else if (product.article().length() > MAX_ARTICLE_LENGTH) {
            errors.add(CatalogImportError.of("ARTICLE_TOO_LONG", path + ".article",
                    "Артикул длиннее " + MAX_ARTICLE_LENGTH + " символов"));
        } else {
            Integer previous = articleSeen.putIfAbsent(normalized, index);
            if (previous != null) {
                errors.add(CatalogImportError.of("DUPLICATE_ARTICLE", path + ".article",
                        "Артикул '" + product.article() + "' уже встречался в позиции " + previous));
            }
        }

        if (product.id() != null) {
            if (!SUPPLIER_ID.matcher(product.id()).matches()) {
                errors.add(CatalogImportError.of("INVALID_SUPPLIER_ID", path + ".id",
                        "Недопустимый ID поставщика: '" + product.id() + "'"));
            } else {
                Integer previous = supplierSeen.putIfAbsent(product.id(), index);
                if (previous != null) {
                    errors.add(CatalogImportError.of("DUPLICATE_SUPPLIER_ID", path + ".id",
                            "ID поставщика '" + product.id() + "' уже встречался в позиции " + previous));
                }
            }
        }

        if (isBlank(product.name())) {
            errors.add(CatalogImportError.of("MISSING_NAME", path + ".name", "Название обязательно"));
        }
        if (isBlank(product.category())) {
            errors.add(CatalogImportError.of("MISSING_CATEGORY", path + ".category",
                    "Категория обязательна: по ней работают фильтры и правила совместимости"));
        }
        if (isBlank(product.unit())) {
            errors.add(CatalogImportError.of("MISSING_UNIT", path + ".unit",
                    "Единица измерения обязательна: 'шт' и 'м' считаются по-разному"));
        }

        validateQuantityRules(product, path, errors);
        validatePrice(product, path, errors, warnings);
        validateSpecs(product, path, errors, warnings);
        validateCertificates(product, path, errors, warnings);
        validateWarehouses(product, path, errors);
    }

    private void validateQuantityRules(CatalogImportDocument.Product product, String path,
                                       List<CatalogImportError> errors) {
        BigDecimal minimum = product.minimum();
        BigDecimal step = product.step();
        if (minimum == null || minimum.signum() <= 0) {
            errors.add(CatalogImportError.of("INVALID_QUANTITY", path + ".minimum",
                    "Минимальная партия должна быть больше нуля"));
        }
        if (step == null || step.signum() <= 0) {
            errors.add(CatalogImportError.of("INVALID_QUANTITY", path + ".step",
                    "Шаг количества должен быть больше нуля"));
        }
        if (minimum != null && step != null && minimum.signum() > 0 && step.signum() > 0
                && minimum.remainder(step).signum() != 0) {
            errors.add(CatalogImportError.of("INVALID_QUANTITY_STEP", path + ".minimum",
                    "Минимальная партия " + Decimals.quantity(minimum)
                            + " не кратна шагу " + Decimals.quantity(step)));
        }
    }

    private void validatePrice(CatalogImportDocument.Product product, String path,
                               List<CatalogImportError> errors, List<CatalogImportError> warnings) {
        BigDecimal price = product.price();
        if (price == null) {
            warnings.add(CatalogImportError.of("PRICE_UNKNOWN", path + ".price",
                    "Цена не передана: товар импортируется с явно неизвестной ценой"));
            return;
        }
        if (price.signum() < 0) {
            errors.add(CatalogImportError.of("INVALID_PRICE", path + ".price", "Цена не может быть отрицательной"));
        }
        if (isBlank(product.currency()) || product.currency().strip().length() != 3) {
            errors.add(CatalogImportError.of("INVALID_CURRENCY", path + ".currency",
                    "Валюта задаётся тремя буквами (например, KZT)"));
        }
    }

    private void validateSpecs(CatalogImportDocument.Product product, String path,
                               List<CatalogImportError> errors, List<CatalogImportError> warnings) {
        Map<String, String> specs = product.specs();
        if (specs == null || specs.isEmpty()) {
            warnings.add(CatalogImportError.of("SPECS_EMPTY", path + ".specs",
                    "Характеристики пусты: подбор аналогов для такого товара работать не будет"));
            return;
        }
        specs.forEach((key, value) -> {
            if (isBlank(key)) {
                errors.add(CatalogImportError.of("INVALID_SPECS", path + ".specs",
                        "Пустое имя характеристики"));
            } else if (value == null || value.isBlank()) {
                errors.add(CatalogImportError.of("INVALID_SPECS", path + ".specs." + key,
                        "Пустое значение характеристики '" + key + "'"));
            }
        });
    }

    private void validateCertificates(CatalogImportDocument.Product product, String path,
                                      List<CatalogImportError> errors, List<CatalogImportError> warnings) {
        List<ProductCertificate> certificates = product.certificates();
        if (certificates == null || certificates.isEmpty()) {
            warnings.add(CatalogImportError.of("CERTIFICATES_EMPTY", path + ".certificates",
                    "Сертификаты не переданы: карточка товара покажет их отсутствие"));
            return;
        }
        for (int i = 0; i < certificates.size(); i++) {
            ProductCertificate certificate = certificates.get(i);
            String certificatePath = path + ".certificates[" + i + "]";
            if (certificate == null || isBlank(certificate.id())) {
                errors.add(CatalogImportError.of("INVALID_CERTIFICATE", certificatePath + ".id",
                        "ID сертификата обязателен"));
            }
        }
    }

    private void validateWarehouses(CatalogImportDocument.Product product, String path,
                                    List<CatalogImportError> errors) {
        List<CatalogImportDocument.Warehouse> warehouses = product.warehouses();
        if (warehouses == null || warehouses.isEmpty()) {
            errors.add(CatalogImportError.of("MISSING_WAREHOUSES", path + ".warehouses",
                    "Нужна хотя бы одна строка остатка; неизвестный остаток передаётся статусом UNKNOWN"));
            return;
        }
        Set<String> warehouseIds = new HashSet<>();
        for (int i = 0; i < warehouses.size(); i++) {
            CatalogImportDocument.Warehouse warehouse = warehouses.get(i);
            String warehousePath = path + ".warehouses[" + i + "]";
            if (warehouse == null || isBlank(warehouse.warehouseId())) {
                errors.add(CatalogImportError.of("MISSING_WAREHOUSE_ID", warehousePath + ".warehouseId",
                        "ID склада обязателен"));
                continue;
            }
            if (!warehouseIds.add(warehouse.warehouseId().strip())) {
                errors.add(CatalogImportError.of("DUPLICATE_WAREHOUSE", warehousePath + ".warehouseId",
                        "Склад '" + warehouse.warehouseId() + "' встречается дважды у одного товара"));
            }
            validateWarehouseQuantity(warehouse, warehousePath, errors);
        }
    }

    private void validateWarehouseQuantity(CatalogImportDocument.Warehouse warehouse, String path,
                                           List<CatalogImportError> errors) {
        StockStatus status;
        try {
            status = StockStatus.valueOf(String.valueOf(warehouse.status()).strip().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            errors.add(CatalogImportError.of("INVALID_STOCK_STATUS", path + ".status",
                    "Неизвестный статус остатка: '" + warehouse.status() + "'"));
            return;
        }

        BigDecimal quantity = warehouse.availableQuantity();
        if (quantity != null && quantity.signum() < 0) {
            errors.add(CatalogImportError.of("INVALID_QUANTITY", path + ".availableQuantity",
                    "Остаток не может быть отрицательным"));
            return;
        }
        switch (status) {
            case UNKNOWN, ON_ORDER -> {
                if (quantity != null) {
                    errors.add(CatalogImportError.of("STOCK_QUANTITY_MISMATCH", path + ".availableQuantity",
                            "Статус " + status + " не имеет известного количества"));
                }
            }
            case OUT_OF_STOCK -> {
                if (quantity == null || quantity.signum() != 0) {
                    errors.add(CatalogImportError.of("STOCK_QUANTITY_MISMATCH", path + ".availableQuantity",
                            "OUT_OF_STOCK — это точный ноль, а не пропуск и не положительное число"));
                }
            }
            case IN_STOCK -> {
                if (quantity == null || quantity.signum() <= 0) {
                    errors.add(CatalogImportError.of("STOCK_QUANTITY_MISMATCH", path + ".availableQuantity",
                            "IN_STOCK требует положительного количества; неизвестность передаётся статусом UNKNOWN"));
                }
            }
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /** Результат проверки: блокирующие ошибки и непустые, но не блокирующие замечания. */
    public record Result(List<CatalogImportError> errors, List<CatalogImportError> warnings) {

        public boolean valid() {
            return errors.isEmpty();
        }
    }
}
