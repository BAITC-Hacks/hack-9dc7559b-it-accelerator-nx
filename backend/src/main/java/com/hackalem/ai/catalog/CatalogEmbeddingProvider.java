package com.hackalem.ai.catalog;

import java.util.List;

/**
 * Источник векторов для каталога.
 *
 * {@link #vectorSpace()} записывается в версию каталога и сверяется при поиске:
 * векторы живой модели и детерминированной заглушки несравнимы, смешивать их
 * в одном индексе нельзя. Вызовы провайдера выполняются вне транзакций БД.
 */
public interface CatalogEmbeddingProvider {

    String vectorSpace();

    String model();

    int dimensions();

    /** Векторы для батча текстов; порядок результата совпадает с порядком входа. */
    List<float[]> embed(List<String> texts);

    default float[] embedQuery(String text) {
        return embed(List.of(text)).get(0);
    }
}
