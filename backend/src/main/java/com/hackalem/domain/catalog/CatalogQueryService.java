package com.hackalem.domain.catalog;

import com.hackalem.ai.catalog.CatalogEmbeddingProvider;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

/**
 * Чтение активной версии каталога.
 *
 * Точный артикул ищется реляционно, без вызова модели: это дешевле и не зависит
 * от доступности провайдера. Отсутствующий артикул — ошибка, а не «похожий товар».
 */
@Service
public class CatalogQueryService {

    private final CatalogRepository catalog;
    private final CatalogEmbeddingProvider embeddings;

    public CatalogQueryService(CatalogRepository catalog, CatalogEmbeddingProvider embeddings) {
        this.catalog = catalog;
        this.embeddings = embeddings;
    }

    public CatalogProduct findByArticle(String article) {
        return catalog.findActiveByArticle(article)
                .orElseThrow(() -> new ProductNotFoundException(article));
    }

    public SearchResult search(String query, int limit, String category, String brand,
                               BigDecimal minPrice, BigDecimal maxPrice) {
        CatalogVersion active = requireSearchableVersion();
        float[] vector = embeddings.embedQuery(query);
        List<CatalogProduct> items = catalog.semanticSearch(vector, limit, category, brand, minPrice, maxPrice);
        return new SearchResult(items, active);
    }

    public CatalogVersion activeVersion() {
        return catalog.findActiveVersion().orElseThrow(() -> new CatalogIndexNotReadyException(
                "CATALOG_NOT_INITIALIZED", "Активной версии каталога нет: импорт ещё не выполнялся"));
    }

    /**
     * Семантический поиск возможен только по индексу, построенному тем же
     * провайдером: векторы live-модели и заглушки несравнимы, и молчаливая
     * выдача «ближайших» по чужому пространству была бы враньём.
     */
    private CatalogVersion requireSearchableVersion() {
        CatalogVersion active = activeVersion();
        if (active.embeddingStatus() != EmbeddingStatus.READY) {
            throw new CatalogIndexNotReadyException("CATALOG_INDEX_NOT_READY",
                    "Семантический индекс версии " + active.id() + " не построен (" + active.embeddingStatus()
                            + "); точный поиск по артикулу доступен");
        }
        if (!embeddings.vectorSpace().equals(active.vectorSpace())) {
            throw new CatalogIndexNotReadyException("CATALOG_VECTOR_SPACE_MISMATCH",
                    "Индекс построен в пространстве " + active.vectorSpace()
                            + ", а сервис работает в " + embeddings.vectorSpace() + ": нужна переиндексация");
        }
        return active;
    }

    /** Результат поиска вместе с версией каталога, по которой он получен. */
    public record SearchResult(List<CatalogProduct> items, CatalogVersion version) {
    }
}
