package com.hackalem.catalog;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hackalem.domain.catalog.CatalogImportDocument;
import com.hackalem.domain.catalog.CatalogImportError;
import com.hackalem.domain.catalog.CatalogImportValidator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Валидатор проверяется на настоящих fixtures DATA-01, а не на выдуманных
 * примерах: если данные и импорт разойдутся, тест это покажет.
 */
class CatalogImportValidatorTest {

    // Как и ObjectMapper Spring Boot: лишние поля fixture (expectedError) не ломают чтение.
    private static final ObjectMapper JSON = new ObjectMapper()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private static final File DATA = new File("../data/sample_catalog");

    private final CatalogImportValidator validator = new CatalogImportValidator();

    private static CatalogImportDocument read(String file) throws Exception {
        return JSON.readValue(new File(DATA, file), CatalogImportDocument.class);
    }

    private static List<String> codes(List<CatalogImportError> errors) {
        return errors.stream().map(CatalogImportError::code).toList();
    }

    @Test
    @DisplayName("Синтетический каталог DATA-01 проходит валидацию целиком")
    void acceptsSampleCatalog() throws Exception {
        CatalogImportDocument document = read("products.json");

        CatalogImportValidator.Result result = validator.validate(document);

        assertThat(document.products()).hasSizeGreaterThanOrEqualTo(30);
        assertThat(result.errors()).isEmpty();
        assertThat(result.valid()).isTrue();
    }

    @Test
    @DisplayName("Дубль артикула отклоняется кодом DUPLICATE_ARTICLE")
    void rejectsDuplicateArticle() throws Exception {
        CatalogImportDocument invalid = withProducts(read("invalid/duplicate-article.json"));

        CatalogImportValidator.Result result = validator.validate(invalid);

        assertThat(codes(result.errors())).contains("DUPLICATE_ARTICLE");
    }

    @Test
    @DisplayName("Отрицательный остаток отклоняется кодом INVALID_QUANTITY")
    void rejectsNegativeQuantity() throws Exception {
        CatalogImportDocument invalid = withProducts(read("invalid/negative-quantity.json"));

        CatalogImportValidator.Result result = validator.validate(invalid);

        assertThat(codes(result.errors())).contains("INVALID_QUANTITY");
    }

    @Test
    @DisplayName("IN_STOCK без количества — ошибка: неизвестность передаётся статусом UNKNOWN")
    void rejectsInStockWithoutQuantity() {
        CatalogImportDocument document = document(product(List.of(
                new CatalogImportDocument.Warehouse("ALA", null, "IN_STOCK", true))));

        CatalogImportValidator.Result result = validator.validate(document);

        assertThat(codes(result.errors())).contains("STOCK_QUANTITY_MISMATCH");
    }

    @Test
    @DisplayName("UNKNOWN с количеством — ошибка: у неизвестного остатка числа нет")
    void rejectsUnknownWithQuantity() {
        CatalogImportDocument document = document(product(List.of(
                new CatalogImportDocument.Warehouse("ALA", new BigDecimal("1"), "UNKNOWN", true))));

        CatalogImportValidator.Result result = validator.validate(document);

        assertThat(codes(result.errors())).contains("STOCK_QUANTITY_MISMATCH");
    }

    @Test
    @DisplayName("OUT_OF_STOCK должен быть точным нулём")
    void rejectsOutOfStockWithoutZero() {
        CatalogImportDocument document = document(product(List.of(
                new CatalogImportDocument.Warehouse("ALA", null, "OUT_OF_STOCK", true))));

        CatalogImportValidator.Result result = validator.validate(document);

        assertThat(codes(result.errors())).contains("STOCK_QUANTITY_MISMATCH");
    }

    @Test
    @DisplayName("Неизвестный статус остатка не проходит молча")
    void rejectsUnknownStatusValue() {
        CatalogImportDocument document = document(product(List.of(
                new CatalogImportDocument.Warehouse("ALA", new BigDecimal("1"), "MAYBE", true))));

        assertThat(codes(validator.validate(document).errors())).contains("INVALID_STOCK_STATUS");
    }

    @Test
    @DisplayName("Минимальная партия обязана быть кратной шагу")
    void rejectsMinimumNotMultipleOfStep() {
        CatalogImportDocument.Product source = product(List.of(
                new CatalogImportDocument.Warehouse("ALA", new BigDecimal("5"), "IN_STOCK", true)));
        CatalogImportDocument.Product broken = new CatalogImportDocument.Product(source.id(),
                source.article(), source.name(), source.brand(), source.category(), source.unit(),
                new BigDecimal("0.7"), new BigDecimal("0.5"), source.price(), source.currency(),
                source.specs(), source.certificates(), source.sourceUrl(), source.sourceVersion(),
                source.synthetic(), source.warehouses());

        assertThat(codes(validator.validate(document(broken)).errors())).contains("INVALID_QUANTITY_STEP");
    }

    @Test
    @DisplayName("Товар без строк остатка не импортируется: пустота — не ноль")
    void rejectsMissingWarehouses() {
        CatalogImportDocument document = document(product(List.of()));

        assertThat(codes(validator.validate(document).errors())).contains("MISSING_WAREHOUSES");
    }

    @Test
    @DisplayName("Неподдерживаемая версия схемы отклоняется явно")
    void rejectsUnsupportedSchemaVersion() {
        CatalogImportDocument document = new CatalogImportDocument(2, true, "synthetic-v1",
                List.of(product(List.of(new CatalogImportDocument.Warehouse("ALA",
                        new BigDecimal("1"), "IN_STOCK", true)))));

        assertThat(codes(validator.validate(document).errors())).contains("UNSUPPORTED_SCHEMA_VERSION");
    }

    @Test
    @DisplayName("Неизвестная цена — предупреждение, а не отказ импорта")
    void warnsOnUnknownPrice() {
        CatalogImportDocument.Product source = product(List.of(
                new CatalogImportDocument.Warehouse("ALA", new BigDecimal("1"), "IN_STOCK", true)));
        CatalogImportDocument.Product noPrice = new CatalogImportDocument.Product(source.id(),
                source.article(), source.name(), source.brand(), source.category(), source.unit(),
                source.minimum(), source.step(), null, source.currency(), source.specs(),
                source.certificates(), source.sourceUrl(), source.sourceVersion(), source.synthetic(),
                source.warehouses());

        CatalogImportValidator.Result result = validator.validate(document(noPrice));

        assertThat(result.valid()).isTrue();
        assertThat(codes(result.warnings())).contains("PRICE_UNKNOWN");
    }

    /** Негативные fixtures не несут schemaVersion/version — добавляем обёртку. */
    private static CatalogImportDocument withProducts(CatalogImportDocument document) {
        return new CatalogImportDocument(1, true, "synthetic-v1", new ArrayList<>(document.products()));
    }

    private static CatalogImportDocument document(CatalogImportDocument.Product... products) {
        return new CatalogImportDocument(1, true, "synthetic-v1", List.of(products));
    }

    private static CatalogImportDocument.Product product(List<CatalogImportDocument.Warehouse> warehouses) {
        return new CatalogImportDocument.Product("1001", "000001", "Автомат SYN C16 1P 01", "SYNTHETIC",
                "breakers", "pcs", BigDecimal.ONE, BigDecimal.ONE, new BigDecimal("1500.00"), "KZT",
                Map.of("currentA", "16"), List.of(), "https://example.invalid/products/000001",
                "synthetic-v1", true, warehouses);
    }
}
