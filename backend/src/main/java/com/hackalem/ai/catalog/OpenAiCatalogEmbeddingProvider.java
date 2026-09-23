package com.hackalem.ai.catalog;

import org.springframework.ai.embedding.EmbeddingModel;

import java.util.ArrayList;
import java.util.List;

/**
 * Живые embeddings через Spring AI. Тексты режутся на батчи: один HTTP-вызов
 * на весь каталог упирается в лимит запроса, а вызов на товар — в rate limit.
 */
public class OpenAiCatalogEmbeddingProvider implements CatalogEmbeddingProvider {

    private final EmbeddingModel embeddingModel;
    private final String model;
    private final int dimensions;
    private final int batchSize;

    public OpenAiCatalogEmbeddingProvider(EmbeddingModel embeddingModel, String model,
                                          int dimensions, int batchSize) {
        this.embeddingModel = embeddingModel;
        this.model = model;
        this.dimensions = dimensions;
        this.batchSize = Math.max(1, batchSize);
    }

    @Override
    public String vectorSpace() {
        return "live:" + model + ":" + dimensions;
    }

    @Override
    public String model() {
        return model;
    }

    @Override
    public int dimensions() {
        return dimensions;
    }

    @Override
    public List<float[]> embed(List<String> texts) {
        List<float[]> vectors = new ArrayList<>(texts.size());
        for (int from = 0; from < texts.size(); from += batchSize) {
            int to = Math.min(from + batchSize, texts.size());
            vectors.addAll(embeddingModel.embed(texts.subList(from, to)));
        }
        return vectors;
    }
}
