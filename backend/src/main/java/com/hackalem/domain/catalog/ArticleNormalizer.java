package com.hackalem.domain.catalog;

/**
 * Нормализация артикула для поиска и уникальности: trim + upper case.
 * Ведущие нули и значимые символы сохраняются — '000001' не превращается в '1'.
 * Исходное написание артикула всегда хранится отдельно (products.article).
 */
public final class ArticleNormalizer {

    private ArticleNormalizer() {
    }

    public static String normalize(String article) {
        if (article == null) {
            return null;
        }
        String trimmed = article.strip();
        return trimmed.isEmpty() ? null : trimmed.toUpperCase(java.util.Locale.ROOT);
    }
}
