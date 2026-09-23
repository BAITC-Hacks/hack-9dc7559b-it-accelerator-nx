package com.hackalem.domain.catalog;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Доступ к versioned каталогу.
 *
 * Здесь нет вызовов провайдера: векторы приходят уже посчитанными, чтобы сетевой
 * вызов не держал транзакцию БД. Запись импорта и публикация версии разделены:
 * упавший батч остаётся в непубликованной версии и не трогает активные данные.
 */
@Repository
public class CatalogRepository {

    private static final String PRODUCT_COLUMNS = """
            SELECT p.id, p.supplier_id, p.article, p.article_normalized,
                   pv.name, pv.brand, pv.category, pv.unit,
                   pv.minimum_quantity, pv.step_quantity, pv.specs, pv.certificates,
                   pv.source_url, pv.source_version, pv.synthetic,
                   cv.id AS catalog_version_id,
                   o.price, o.currency,
                   o.source_version AS price_source_version, o.observed_at AS price_observed_at
            """;

    private static final String ACTIVE_PRODUCT_FROM = """
            FROM catalog_versions cv
            JOIN product_versions pv ON pv.catalog_version_id = cv.id
            JOIN products p ON p.id = pv.product_id
            LEFT JOIN product_offers o ON o.catalog_version_id = cv.id AND o.product_id = p.id
            WHERE cv.status = 'ACTIVE' AND p.status = 'ACTIVE'
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public CatalogRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    // ─── Версии ──────────────────────────────────────────────────────────────

    public Optional<CatalogVersion> findActiveVersion() {
        return jdbc.query("SELECT * FROM catalog_versions WHERE status = 'ACTIVE'", versionMapper())
                .stream().findFirst();
    }

    public Optional<CatalogVersion> findVersion(long id) {
        return jdbc.query("SELECT * FROM catalog_versions WHERE id = ?", versionMapper(), id)
                .stream().findFirst();
    }

    public long createVersion(String label, String sourceVersion, String vectorSpace,
                              String embeddingModel, int dimensions, String contentHash, long importJobId) {
        return jdbc.queryForObject("""
                INSERT INTO catalog_versions (label, source_version, status, embedding_status,
                                              embedding_model, embedding_dimensions, vector_space,
                                              content_hash, import_job_id)
                VALUES (?, ?, 'PENDING', 'PENDING', ?, ?, ?, ?, ?)
                RETURNING id
                """, Long.class, label, sourceVersion, embeddingModel, dimensions, vectorSpace,
                contentHash, importJobId);
    }

    public void markVersionReady(long versionId, int productCount, EmbeddingStatus embeddingStatus,
                                 int embeddedCount) {
        jdbc.update("""
                UPDATE catalog_versions
                SET status = 'READY', ready_at = now(), product_count = ?,
                    embedding_status = ?, embedded_count = ?
                WHERE id = ?
                """, productCount, embeddingStatus.name(), embeddedCount, versionId);
    }

    public void markVersionFailed(long versionId, String code, String message) {
        jdbc.update("""
                UPDATE catalog_versions
                SET status = 'FAILED', failed_at = now(), error_code = ?, error_message = ?
                WHERE id = ? AND status <> 'ACTIVE'
                """, code, truncate(message, 4000), versionId);
    }

    /**
     * Публикация версии: снимаем прежнюю активную и переводим готовую в ACTIVE
     * одной транзакцией. Если версия уже не READY — публикацию перехватил кто-то
     * другой, и мы откатываемся, не тронув активные данные.
     */
    @Transactional
    public void publishVersion(long versionId) {
        publishSyntheticSource(versionId);
        jdbc.update("UPDATE catalog_versions SET status = 'SUPERSEDED', superseded_at = now() WHERE status = 'ACTIVE'");
        int updated = jdbc.update("""
                UPDATE catalog_versions SET status = 'ACTIVE', published_at = now()
                WHERE id = ? AND status = 'READY'
                """, versionId);
        if (updated != 1) {
            throw new CatalogConflictException("CATALOG_PUBLISH_CONFLICT",
                    "Версия " + versionId + " не в состоянии READY: публикацию выполнил другой импорт");
        }
    }

    /** D1 confirmation reads sample_offers directly: projection must publish atomically, never lazily alone. */
    private void publishSyntheticSource(long versionId) {
        // CAT-01 can still run before the later D2 service migration is installed.
        if (!Boolean.TRUE.equals(jdbc.queryForObject("SELECT to_regclass('catalog_offer_seed') IS NOT NULL",Boolean.class))) return;
        jdbc.update("""
            DELETE FROM sample_offers so WHERE so.catalog_version_id IS NOT NULL AND NOT EXISTS (
              SELECT 1 FROM product_versions pv JOIN products p ON p.id=pv.product_id
              JOIN warehouse_stock w ON w.catalog_version_id=pv.catalog_version_id AND w.product_id=p.id
              JOIN product_offers o ON o.catalog_version_id=pv.catalog_version_id AND o.product_id=p.id
              WHERE pv.catalog_version_id=? AND pv.synthetic AND w.eligible
                AND w.status IN ('IN_STOCK','OUT_OF_STOCK') AND w.available_quantity IS NOT NULL
                AND p.article=so.article AND pv.unit=so.unit AND w.warehouse_id=so.warehouse)
            """,versionId);
        var rows=jdbc.queryForList("""
            SELECT p.article,pv.unit,w.warehouse_id,o.price,o.currency,w.available_quantity,
                   pv.step_quantity,pv.minimum_quantity,pv.source_version
            FROM product_versions pv JOIN products p ON p.id=pv.product_id
            JOIN warehouse_stock w ON w.catalog_version_id=pv.catalog_version_id AND w.product_id=p.id
            JOIN product_offers o ON o.catalog_version_id=pv.catalog_version_id AND o.product_id=p.id
            WHERE pv.catalog_version_id=? AND pv.synthetic AND w.eligible
              AND w.status IN ('IN_STOCK','OUT_OF_STOCK') AND w.available_quantity IS NOT NULL
            """,versionId);
        for(var row:rows) jdbc.update("""
            INSERT INTO sample_offers(article,unit,warehouse,bucket,price,currency,available,step,
                version,catalog_version_id,source_version,minimum_quantity)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(article,unit,warehouse) DO UPDATE SET
                price=EXCLUDED.price,currency=EXCLUDED.currency,available=EXCLUDED.available,
                step=EXCLUDED.step,version=sample_offers.version+1,catalog_version_id=EXCLUDED.catalog_version_id,
                source_version=EXCLUDED.source_version,minimum_quantity=EXCLUDED.minimum_quantity,observed_at=now()
            """,row.get("article"),row.get("unit"),row.get("warehouse_id"),
                CatalogOfferService.bucket((String)row.get("article"),(String)row.get("unit"),(String)row.get("warehouse_id")),
                row.get("price"),row.get("currency"),row.get("available_quantity"),row.get("step_quantity"),
                versionId,versionId,row.get("source_version"),row.get("minimum_quantity"));
        jdbc.update("""
            INSERT INTO catalog_offer_seed(product_id,catalog_version_id,source_version)
            SELECT product_id,catalog_version_id,source_version FROM product_versions
            WHERE catalog_version_id=? AND synthetic
            ON CONFLICT(product_id) DO UPDATE SET catalog_version_id=EXCLUDED.catalog_version_id,source_version=EXCLUDED.source_version
            """,versionId);
    }

    /** Чистка вытесненных версий; идентичность товаров при этом не удаляется. */
    public int deleteSupersededBeyond(int retained) {
        return jdbc.update("""
                DELETE FROM catalog_versions
                WHERE status = 'SUPERSEDED' AND id NOT IN (
                    SELECT id FROM catalog_versions WHERE status = 'SUPERSEDED'
                    ORDER BY COALESCE(superseded_at, created_at) DESC LIMIT ?)
                """, Math.max(0, retained));
    }

    // ─── Запись содержимого версии ───────────────────────────────────────────

    /**
     * Запись подготовленного батча в непубликованную версию.
     * Транзакция короткая и чисто SQL: embeddings посчитаны до вызова.
     */
    @Transactional
    public StageResult stage(long versionId, List<StagedProduct> products, String sourceVersion) {
        List<CatalogImportError> warnings = new ArrayList<>();
        List<Object[]> contentRows = new ArrayList<>();
        List<Object[]> offerRows = new ArrayList<>();
        List<Object[]> stockRows = new ArrayList<>();

        for (StagedProduct staged : products) {
            CatalogImportDocument.Product source = staged.source();
            long productId = resolveProductId(source, warnings);

            contentRows.add(new Object[]{versionId, productId, source.name(), source.brand(),
                    source.category(), source.unit(), source.minimum(), source.step(),
                    writeJson(source.specs() == null ? Map.of() : source.specs()),
                    writeJson(source.certificates() == null ? List.of() : source.certificates()),
                    source.sourceUrl(), versionSourceOf(source, sourceVersion),
                    Boolean.TRUE.equals(source.synthetic()), staged.searchText(),
                    vectorLiteral(staged.embedding())});

            if (source.price() != null) {
                offerRows.add(new Object[]{versionId, productId, source.price(),
                        source.currency() == null ? "KZT" : source.currency().strip().toUpperCase(Locale.ROOT),
                        versionSourceOf(source, sourceVersion)});
            }
            for (CatalogImportDocument.Warehouse warehouse : source.warehouses()) {
                StockStatus status = StockStatus.valueOf(warehouse.status().strip().toUpperCase(Locale.ROOT));
                stockRows.add(new Object[]{versionId, productId, warehouse.warehouseId().strip(),
                        warehouse.availableQuantity(), status.name(),
                        !Boolean.FALSE.equals(warehouse.eligible()),
                        versionSourceOf(source, sourceVersion)});
            }
        }

        jdbc.batchUpdate("""
                INSERT INTO product_versions (catalog_version_id, product_id, name, brand, category,
                                              unit, minimum_quantity, step_quantity, specs, certificates,
                                              source_url, source_version, synthetic, search_text, embedding)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), CAST(? AS jsonb), ?, ?, ?, ?, CAST(? AS vector))
                """, contentRows);
        jdbc.batchUpdate("""
                INSERT INTO product_offers (catalog_version_id, product_id, price, currency, source_version)
                VALUES (?, ?, ?, ?, ?)
                """, offerRows);
        jdbc.batchUpdate("""
                INSERT INTO warehouse_stock (catalog_version_id, product_id, warehouse_id,
                                             available_quantity, status, eligible, source_version)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, stockRows);

        return new StageResult(contentRows.size(), warnings);
    }

    /**
     * Идентичность товара. Сопоставление идёт по нормализованному артикулу —
     * это бизнес-ключ каталога. Уже существующий id товара НИКОГДА не меняется,
     * даже если поставщик прислал другой: ссылки из прошлых диалогов и корзин
     * должны продолжать указывать на тот же товар.
     */
    private long resolveProductId(CatalogImportDocument.Product source, List<CatalogImportError> warnings) {
        String normalized = ArticleNormalizer.normalize(source.article());
        List<Map<String, Object>> existing = jdbc.queryForList(
                "SELECT id, supplier_id FROM products WHERE article_normalized = ? AND status = 'ACTIVE'",
                normalized);

        if (!existing.isEmpty()) {
            long productId = ((Number) existing.get(0).get("id")).longValue();
            String currentSupplierId = (String) existing.get(0).get("supplier_id");
            String incomingSupplierId = supplierIdOrNull(source.id(), productId, warnings, normalized);
            jdbc.update("UPDATE products SET article = ?, supplier_id = COALESCE(?, supplier_id), updated_at = now() WHERE id = ?",
                    source.article(), incomingSupplierId, productId);
            if (incomingSupplierId != null && currentSupplierId != null
                    && !incomingSupplierId.equals(currentSupplierId)) {
                warnings.add(CatalogImportError.of("SUPPLIER_ID_CHANGED", normalized,
                        "У товара сменился ID поставщика: " + currentSupplierId + " → " + incomingSupplierId
                                + "; внутренний id " + productId + " сохранён"));
            }
            return productId;
        }

        long productId = allocateProductId(source.id(), warnings, normalized);
        String supplierId = supplierIdOrNull(source.id(), productId, warnings, normalized);
        jdbc.update("""
                INSERT INTO products (id, supplier_id, article, article_normalized, status)
                VALUES (?, ?, ?, ?, 'ACTIVE')
                """, productId, supplierId, source.article(), normalized);
        return productId;
    }

    /**
     * Новый id: числовой ID поставщика, если он свободен, иначе значение
     * последовательности. hashCode артикула не используется — он даёт коллизии
     * ('Aa' и 'BB' совпадают) и затирал бы чужой товар.
     */
    private long allocateProductId(String supplierId, List<CatalogImportError> warnings, String normalized) {
        Long candidate = positiveLongOrNull(supplierId);
        if (candidate != null) {
            Integer taken = jdbc.queryForObject("SELECT count(*) FROM products WHERE id = ?", Integer.class, candidate);
            if (taken != null && taken == 0) {
                return candidate;
            }
            warnings.add(CatalogImportError.of("SUPPLIER_ID_TAKEN", normalized,
                    "ID поставщика " + supplierId + " занят другим товаром: выдан собственный id"));
        }
        return jdbc.queryForObject("SELECT nextval('products_generated_id_seq')", Long.class);
    }

    /** ID поставщика уникален среди действующих товаров; занятый — не присваиваем. */
    private String supplierIdOrNull(String supplierId, long productId, List<CatalogImportError> warnings,
                                    String normalized) {
        if (supplierId == null || supplierId.isBlank()) {
            return null;
        }
        Integer taken = jdbc.queryForObject("""
                SELECT count(*) FROM products
                WHERE supplier_id = ? AND status = 'ACTIVE' AND id <> ?
                """, Integer.class, supplierId, productId);
        if (taken != null && taken > 0) {
            warnings.add(CatalogImportError.of("SUPPLIER_ID_TAKEN", normalized,
                    "ID поставщика " + supplierId + " уже закреплён за другим товаром"));
            return null;
        }
        return supplierId;
    }

    // ─── Чтение активной версии ──────────────────────────────────────────────

    public Optional<CatalogProduct> findActiveByArticle(String article) {
        String normalized = ArticleNormalizer.normalize(article);
        if (normalized == null) {
            return Optional.empty();
        }
        List<CatalogProduct> found = jdbc.query(
                PRODUCT_COLUMNS + ACTIVE_PRODUCT_FROM + " AND p.article_normalized = ?",
                productMapper(), normalized);
        return hydrateStock(found).stream().findFirst();
    }

    public Optional<CatalogProduct> findByArticle(long versionId, String article) {
        return hydrateStock(jdbc.query(PRODUCT_COLUMNS + ACTIVE_PRODUCT_FROM.replace("cv.status = 'ACTIVE'", "cv.id = ?")
                + " AND p.article_normalized = ?",productMapper(),versionId,ArticleNormalizer.normalize(article))).stream().findFirst();
    }

    public List<CatalogProduct> candidates(long versionId,String category,String brand,int limit) {
        StringBuilder sql=new StringBuilder(PRODUCT_COLUMNS).append(ACTIVE_PRODUCT_FROM.replace("cv.status = 'ACTIVE'","cv.id = ?"));
        List<Object> args=new ArrayList<>();args.add(versionId);
        if(category!=null&&!category.isBlank()){sql.append(" AND pv.category=?");args.add(category);}
        if(brand!=null&&!brand.isBlank()){sql.append(" AND pv.brand=?");args.add(brand);}
        sql.append(" ORDER BY p.id LIMIT ?");args.add(Math.min(limit,2000));
        return hydrateStock(jdbc.query(sql.toString(),productMapper(),args.toArray()));
    }

    /** Query embeddings are prepared before entering this short SQL-only transaction. */
    @Transactional(readOnly=true)
    public List<CatalogProduct> semanticSearch(long versionId,float[] queryVector,int limit,String category,
                                               String brand,Map<String,String> specs,boolean exactBaseline) {
        if(exactBaseline)jdbc.execute("SET LOCAL enable_indexscan=off");
        else jdbc.execute("SET LOCAL hnsw.ef_search=100");
        String vector=vectorLiteral(queryVector);
        StringBuilder sql=new StringBuilder(PRODUCT_COLUMNS)
                .append(", 1 - (pv.embedding <=> CAST(? AS vector)) AS score\n")
                .append(ACTIVE_PRODUCT_FROM.replace("cv.status = 'ACTIVE'","cv.id = ?"))
                .append(" AND pv.embedding IS NOT NULL");
        List<Object> args=new ArrayList<>();args.add(vector);args.add(versionId);
        if(category!=null&&!category.isBlank()){sql.append(" AND pv.category=?");args.add(category);}
        if(brand!=null&&!brand.isBlank()){sql.append(" AND pv.brand=?");args.add(brand);}
        if(specs!=null&&!specs.isEmpty()){sql.append(" AND pv.specs @> CAST(? AS jsonb)");args.add(writeJson(specs));}
        sql.append(" ORDER BY pv.embedding <=> CAST(? AS vector) LIMIT ?");args.add(vector);args.add(Math.min(limit,100));
        return hydrateStock(jdbc.query(sql.toString(),productMapper(),args.toArray()));
    }

    /** Остатки подтягиваются одним запросом на весь результат, а не по товару. */
    private List<CatalogProduct> hydrateStock(List<CatalogProduct> products) {
        if (products.isEmpty()) {
            return products;
        }
        List<Long> ids = products.stream().map(CatalogProduct::id).toList();
        String placeholders = String.join(",", ids.stream().map(id -> "?").toList());
        long versionId = products.get(0).catalogVersionId();

        Object[] args = new Object[ids.size() + 1];
        args[0] = versionId;
        for (int i = 0; i < ids.size(); i++) {
            args[i + 1] = ids.get(i);
        }

        List<Map.Entry<Long, WarehouseAvailability>> rows = jdbc.query("""
                SELECT product_id, warehouse_id, available_quantity, status, eligible, observed_at
                FROM warehouse_stock
                WHERE catalog_version_id = ? AND product_id IN (%s)
                """.formatted(placeholders),
                (rs, row) -> Map.entry(rs.getLong("product_id"), new WarehouseAvailability(
                        rs.getString("warehouse_id"),
                        StockStatus.valueOf(rs.getString("status")),
                        rs.getBigDecimal("available_quantity"),
                        rs.getBoolean("eligible"),
                        toInstant(rs.getTimestamp("observed_at")))),
                args);

        Map<Long, List<WarehouseAvailability>> byProduct = new HashMap<>();
        for (Map.Entry<Long, WarehouseAvailability> entry : rows) {
            byProduct.computeIfAbsent(entry.getKey(), key -> new ArrayList<>()).add(entry.getValue());
        }

        List<CatalogProduct> hydrated = new ArrayList<>(products.size());
        for (CatalogProduct product : products) {
            hydrated.add(new CatalogProduct(product.id(), product.supplierId(), product.article(),
                    product.articleNormalized(), product.name(), product.brand(), product.category(),
                    product.unit(), product.minimumQuantity(), product.stepQuantity(), product.specs(),
                    product.certificates(), product.sourceUrl(), product.sourceVersion(), product.synthetic(),
                    product.catalogVersionId(), product.offer(),
                    ProductStock.from(byProduct.getOrDefault(product.id(), List.of())), product.score()));
        }
        return hydrated;
    }

    // ─── Мапперы и утилиты ───────────────────────────────────────────────────

    private RowMapper<CatalogProduct> productMapper() {
        return (rs, row) -> {
            BigDecimal price = rs.getBigDecimal("price");
            ProductOffer offer = price == null ? null : new ProductOffer(price,
                    rs.getString("currency"), rs.getString("price_source_version"),
                    toInstant(rs.getTimestamp("price_observed_at")));
            return new CatalogProduct(
                    rs.getLong("id"),
                    rs.getString("supplier_id"),
                    rs.getString("article"),
                    rs.getString("article_normalized"),
                    rs.getString("name"),
                    rs.getString("brand"),
                    rs.getString("category"),
                    rs.getString("unit"),
                    rs.getBigDecimal("minimum_quantity"),
                    rs.getBigDecimal("step_quantity"),
                    readSpecs(rs.getString("specs")),
                    readCertificates(rs.getString("certificates")),
                    rs.getString("source_url"),
                    rs.getString("source_version"),
                    rs.getBoolean("synthetic"),
                    rs.getLong("catalog_version_id"),
                    offer,
                    ProductStock.from(List.of()),
                    hasColumn(rs, "score") ? rs.getDouble("score") : null);
        };
    }

    private RowMapper<CatalogVersion> versionMapper() {
        return (rs, row) -> new CatalogVersion(
                rs.getLong("id"),
                rs.getString("label"),
                rs.getString("source_version"),
                CatalogVersionStatus.valueOf(rs.getString("status")),
                EmbeddingStatus.valueOf(rs.getString("embedding_status")),
                rs.getString("embedding_model"),
                (Integer) rs.getObject("embedding_dimensions"),
                rs.getString("vector_space"),
                rs.getString("content_hash"),
                rs.getInt("product_count"),
                rs.getInt("embedded_count"),
                (Long) rs.getObject("import_job_id"),
                toInstant(rs.getTimestamp("created_at")),
                toInstant(rs.getTimestamp("published_at")));
    }

    private Map<String, String> readSpecs(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<LinkedHashMap<String, String>>() {
            });
        } catch (Exception ex) {
            throw new IllegalStateException("Не удалось прочитать specs товара: " + ex.getMessage(), ex);
        }
    }

    private List<ProductCertificate> readCertificates(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<ProductCertificate>>() {
            });
        } catch (Exception ex) {
            throw new IllegalStateException("Не удалось прочитать сертификаты товара: " + ex.getMessage(), ex);
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalStateException("Не удалось сериализовать данные товара: " + ex.getMessage(), ex);
        }
    }

    private static String versionSourceOf(CatalogImportDocument.Product product, String fallback) {
        return product.sourceVersion() == null || product.sourceVersion().isBlank()
                ? fallback : product.sourceVersion();
    }

    private static Long positiveLongOrNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            long parsed = Long.parseLong(value.strip());
            return parsed > 0 ? parsed : null;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    static String vectorLiteral(float[] vector) {
        if (vector == null) {
            return null;
        }
        StringBuilder result = new StringBuilder("[");
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                result.append(',');
            }
            result.append(vector[i]);
        }
        return result.append(']').toString();
    }

    private static String truncate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static boolean hasColumn(ResultSet rs, String column) throws SQLException {
        var metaData = rs.getMetaData();
        for (int i = 1; i <= metaData.getColumnCount(); i++) {
            if (column.equalsIgnoreCase(metaData.getColumnLabel(i))) {
                return true;
            }
        }
        return false;
    }

    /** Сколько товаров записано в версию и какие замечания это вызвало. */
    public record StageResult(int importedProducts, List<CatalogImportError> warnings) {
    }
}
