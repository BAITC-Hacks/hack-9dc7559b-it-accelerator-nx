package com.hackalem.catalog;

import com.hackalem.domain.catalog.ArticleNormalizer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ArticleNormalizerTest {

    @Test
    @DisplayName("Ведущие нули значимы: '000001' не схлопывается в '1'")
    void keepsLeadingZeros() {
        assertThat(ArticleNormalizer.normalize("000001")).isEqualTo("000001");
        assertThat(ArticleNormalizer.normalize("000001")).isNotEqualTo("1");
    }

    @Test
    @DisplayName("Нормализация — только пробелы и регистр")
    void trimsAndUppercases() {
        assertThat(ArticleNormalizer.normalize("  000013 ")).isEqualTo("000013");
        assertThat(ArticleNormalizer.normalize("aa")).isEqualTo("AA");
    }

    @Test
    @DisplayName("Пустой артикул — это отсутствие артикула, а не пустая строка")
    void blankIsNull() {
        assertThat(ArticleNormalizer.normalize(null)).isNull();
        assertThat(ArticleNormalizer.normalize("   ")).isNull();
    }

    @Test
    @DisplayName("'Aa' и 'BB' совпадают по String.hashCode(), но остаются разными артикулами")
    void hashCodeCollisionDoesNotMergeArticles() {
        assertThat("Aa".hashCode()).isEqualTo("BB".hashCode());
        assertThat(ArticleNormalizer.normalize("Aa")).isNotEqualTo(ArticleNormalizer.normalize("BB"));
    }
}
