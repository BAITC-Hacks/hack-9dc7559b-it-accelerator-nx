package com.hackalem.ai.catalog;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.CRC32;

/**
 * Детерминированные векторы без внешнего вызова: feature hashing по словам.
 *
 * Нужны, чтобы импорт, индексация и поиск проверялись offline и в тестах без
 * оплаченного ключа. Это не замена модели: качество ранжирования такими
 * векторами не доказывает ничего о живом поиске, поэтому пространство векторов
 * помечено как fake и в один индекс с live не попадает.
 */
public class DeterministicCatalogEmbeddingProvider implements CatalogEmbeddingProvider {

    private static final String GENERATOR = "deterministic-v1";

    private final int dimensions;

    public DeterministicCatalogEmbeddingProvider(int dimensions) {
        this.dimensions = dimensions;
    }

    @Override
    public String vectorSpace() {
        return "fake:" + GENERATOR + ":" + dimensions;
    }

    @Override
    public String model() {
        return GENERATOR;
    }

    @Override
    public int dimensions() {
        return dimensions;
    }

    @Override
    public List<float[]> embed(List<String> texts) {
        List<float[]> vectors = new ArrayList<>(texts.size());
        for (String text : texts) {
            vectors.add(vectorOf(text));
        }
        return vectors;
    }

    private float[] vectorOf(String text) {
        float[] vector = new float[dimensions];
        String normalized = text == null ? "" : text.toLowerCase(Locale.ROOT);
        for (String token : normalized.split("[^\\p{L}\\p{N}.]+")) {
            if (token.isBlank()) {
                continue;
            }
            long hash = hash(token);
            int index = (int) Math.floorMod(hash, dimensions);
            vector[index] += (hash & 1L) == 0L ? 1f : -1f;
        }
        return normalize(vector);
    }

    private static long hash(String token) {
        CRC32 crc = new CRC32();
        crc.update(token.getBytes(StandardCharsets.UTF_8));
        return crc.getValue();
    }

    private static float[] normalize(float[] vector) {
        double length = 0;
        for (float value : vector) {
            length += (double) value * value;
        }
        if (length == 0) {
            // Пустой текст: косинусное расстояние к нулевому вектору не определено.
            vector[0] = 1f;
            return vector;
        }
        float norm = (float) Math.sqrt(length);
        for (int i = 0; i < vector.length; i++) {
            vector[i] /= norm;
        }
        return vector;
    }
}
