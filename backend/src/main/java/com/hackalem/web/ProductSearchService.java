package com.hackalem.web;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Service
public class ProductSearchService {
    private final JdbcTemplate jdbc;
    private final org.springframework.beans.factory.ObjectProvider<EmbeddingModel> embeddingModel;

    public ProductSearchService(JdbcTemplate jdbc, org.springframework.beans.factory.ObjectProvider<EmbeddingModel> embeddingModel) {
        this.jdbc = jdbc;
        this.embeddingModel = embeddingModel;
    }

    public List<ProductSearchResult> search(String query, int limit, String category,
                                            BigDecimal minPrice, BigDecimal maxPrice) {
        String vector = vectorLiteral(embeddings().embed(query));
        StringBuilder sql = new StringBuilder("""
                SELECT id, article, name, brand, category, price, currency, stock, source_url,
                       1 - (embedding <=> CAST(? AS vector)) AS score
                FROM products
                WHERE embedding IS NOT NULL
                """);
        List<Object> args = new ArrayList<>(List.of(vector));
        if (category != null && !category.isBlank()) {
            sql.append(" AND category = ?");
            args.add(category);
        }
        if (minPrice != null) {
            sql.append(" AND price >= ?");
            args.add(minPrice);
        }
        if (maxPrice != null) {
            sql.append(" AND price <= ?");
            args.add(maxPrice);
        }
        sql.append(" ORDER BY embedding <=> CAST(? AS vector) LIMIT ?");
        args.add(vector);
        args.add(limit);
        return jdbc.query(sql.toString(), args.toArray(), (rs, row) -> new ProductSearchResult(
                rs.getLong("id"), rs.getString("article"), rs.getString("name"),
                rs.getString("brand"), rs.getString("category"), rs.getBigDecimal("price"),
                rs.getString("currency"), rs.getBoolean("stock"), rs.getString("source_url"),
                rs.getDouble("score")));
    }

    @Transactional
    public ProductSearchResult upsert(ProductSearchController.ProductRequest request) {
        long id = request.id() == null ? Math.abs(request.article().hashCode()) : request.id();
        String searchText = String.join(". ", request.name(), nullToEmpty(request.brand()),
                nullToEmpty(request.category()), nullToEmpty(request.specs()));
        String vector = vectorLiteral(embeddings().embed(searchText));
        jdbc.update("""
                INSERT INTO products (id, article, name, brand, category, price, currency, stock,
                                      source_url, specs, search_text, embedding, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, COALESCE(?, 'KZT'), COALESCE(?, TRUE), ?,
                        COALESCE(CAST(? AS jsonb), '{}'::jsonb), ?, CAST(? AS vector), now())
                ON CONFLICT (id) DO UPDATE SET article = EXCLUDED.article, name = EXCLUDED.name,
                    brand = EXCLUDED.brand, category = EXCLUDED.category, price = EXCLUDED.price,
                    currency = EXCLUDED.currency, stock = EXCLUDED.stock, source_url = EXCLUDED.source_url,
                    specs = EXCLUDED.specs, search_text = EXCLUDED.search_text,
                    embedding = EXCLUDED.embedding, updated_at = now()
                """, id, request.article(), request.name(), request.brand(), request.category(),
                request.price(), request.currency(), request.stock(), request.sourceUrl(),
                request.specs(), searchText, vector);
        return jdbc.queryForObject("""
                SELECT id, article, name, brand, category, price, currency, stock, source_url, 1.0 AS score
                FROM products WHERE id = ?
                """, (rs, row) -> new ProductSearchResult(rs.getLong("id"), rs.getString("article"),
                rs.getString("name"), rs.getString("brand"), rs.getString("category"),
                rs.getBigDecimal("price"), rs.getString("currency"), rs.getBoolean("stock"),
                rs.getString("source_url"), rs.getDouble("score")), id);
    }

    private static String vectorLiteral(float[] vector) {
        StringBuilder result = new StringBuilder("[");
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) result.append(',');
            result.append(vector[i]);
        }
        return result.append(']').toString();
    }

    private EmbeddingModel embeddings() {
        var model=embeddingModel.getIfAvailable();
        if(model==null) throw ApiException.unavailable("embedding_source_unavailable");
        return model;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    public record ProductSearchResult(@com.fasterxml.jackson.annotation.JsonFormat(shape=com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING)
                                      @io.swagger.v3.oas.annotations.media.Schema(type="string") Long id, String article, String name, String brand,
                                      String category, BigDecimal price, String currency,
                                      boolean stock, String sourceUrl, double score) {
    }
}
