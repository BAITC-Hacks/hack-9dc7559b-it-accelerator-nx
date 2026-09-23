package com.hackalem.domain.catalog;

/**
 * Артикула нет в активной версии каталога. Подменять его похожим товаром
 * запрещено: вызывающая сторона получает 404, а не «ближайший» результат.
 */
public class ProductNotFoundException extends RuntimeException {

    private final String article;

    public ProductNotFoundException(String article) {
        super("Товар с артикулом '" + article + "' не найден в активном каталоге");
        this.article = article;
    }

    public String article() {
        return article;
    }
}
