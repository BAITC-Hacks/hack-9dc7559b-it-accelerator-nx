package com.hackalem.catalog;

import com.hackalem.ai.catalog.DeterministicCatalogEmbeddingProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DeterministicCatalogEmbeddingProviderTest {

    private final DeterministicCatalogEmbeddingProvider provider =
            new DeterministicCatalogEmbeddingProvider(1536);

    @Test
    @DisplayName("Пространство векторов помечено как fake и содержит размерность")
    void vectorSpaceIsMarkedFake() {
        assertThat(provider.vectorSpace()).isEqualTo("fake:deterministic-v1:1536");
    }

    @Test
    @DisplayName("Один и тот же текст даёт один и тот же вектор — индекс воспроизводим")
    void deterministic() {
        float[] first = provider.embedQuery("Автомат SYN C16 1P");
        float[] second = provider.embedQuery("Автомат SYN C16 1P");

        assertThat(first).hasSize(1536).containsExactly(second);
    }

    @Test
    @DisplayName("Похожий текст ближе, чем текст из другой категории")
    void similarTextIsCloser() {
        float[] query = provider.embedQuery("автомат c16 breakers");
        float[] breaker = provider.embedQuery("автомат c16 breakers 1p");
        float[] lamp = provider.embedQuery("лампа led e27 lamps");

        assertThat(cosine(query, breaker)).isGreaterThan(cosine(query, lamp));
    }

    @Test
    @DisplayName("Батч сохраняет порядок входа")
    void keepsBatchOrder() {
        List<float[]> vectors = provider.embed(List.of("первый", "второй"));

        assertThat(vectors).hasSize(2);
        assertThat(vectors.get(0)).containsExactly(provider.embedQuery("первый"));
        assertThat(vectors.get(1)).containsExactly(provider.embedQuery("второй"));
    }

    private static double cosine(float[] left, float[] right) {
        double dot = 0;
        for (int i = 0; i < left.length; i++) {
            dot += (double) left[i] * right[i];
        }
        return dot;
    }
}
