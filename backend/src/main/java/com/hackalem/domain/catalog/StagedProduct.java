package com.hackalem.domain.catalog;

/**
 * Товар, подготовленный к записи: исходные данные импорта плюс текст и вектор,
 * посчитанные заранее. Вектор может быть null — тогда версия публикуется без
 * семантического индекса, а точный поиск по артикулу продолжает работать.
 */
public record StagedProduct(CatalogImportDocument.Product source, String searchText, float[] embedding) {
}
