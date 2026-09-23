package com.hackalem.domain.catalog;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Задания импорта каталога: создание, идемпотентность по ключу и статус. */
@Repository
public class CatalogImportJobRepository {

    private static final String SELECT = """
            SELECT j.*, cv.status AS version_status, cv.embedding_status
            FROM catalog_import_jobs j
            LEFT JOIN catalog_versions cv ON cv.id = j.catalog_version_id
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public CatalogImportJobRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    /**
     * Создаёт задание. Повторный запрос с тем же Idempotency-Key возвращает
     * уже существующее задание, а не второй импорт того же файла.
     */
    public Handle createOrGet(String idempotencyKey, String contentHash, String sourceVersion,
                              String requestedBy, String vectorSpace, int totalProducts) {
        if (idempotencyKey != null) {
            Optional<CatalogImportJob> existing = findByIdempotencyKey(idempotencyKey);
            if (existing.isPresent()) {
                return new Handle(existing.get(), false);
            }
        }
        try {
            long id = jdbc.queryForObject("""
                    INSERT INTO catalog_import_jobs (status, idempotency_key, content_hash, source_version,
                                                     requested_by, vector_space, total_products)
                    VALUES ('PENDING', ?, ?, ?, ?, ?, ?)
                    RETURNING id
                    """, Long.class, idempotencyKey, contentHash, sourceVersion, requestedBy,
                    vectorSpace, totalProducts);
            return new Handle(find(id).orElseThrow(), true);
        } catch (DuplicateKeyException ex) {
            // Гонка двух одинаковых запросов: побеждает первый, второй читает его результат.
            return new Handle(findByIdempotencyKey(idempotencyKey)
                    .orElseThrow(() -> new IllegalStateException("Конфликт ключа идемпотентности", ex)), false);
        }
    }

    /** Задание и признак того, что его создал именно этот вызов (а не повтор). */
    public record Handle(CatalogImportJob job, boolean created) {
    }

    public Optional<CatalogImportJob> find(long id) {
        return jdbc.query(SELECT + " WHERE j.id = ?", mapper(), id).stream().findFirst();
    }

    public Optional<CatalogImportJob> findByIdempotencyKey(String key) {
        return jdbc.query(SELECT + " WHERE j.idempotency_key = ?", mapper(), key).stream().findFirst();
    }

    public void markRunning(long id, long catalogVersionId) {
        jdbc.update("""
                UPDATE catalog_import_jobs
                SET status = 'RUNNING', started_at = COALESCE(started_at, now()), catalog_version_id = ?
                WHERE id = ?
                """, catalogVersionId, id);
    }

    public void markSucceeded(long id, int importedProducts, int embeddedProducts,
                              List<CatalogImportError> warnings) {
        jdbc.update("""
                UPDATE catalog_import_jobs
                SET status = 'SUCCEEDED', finished_at = now(), imported_products = ?,
                    embedded_products = ?, warnings = CAST(? AS jsonb)
                WHERE id = ?
                """, importedProducts, embeddedProducts, writeJson(warnings), id);
    }

    public void markFailed(long id, List<CatalogImportError> errors, List<CatalogImportError> warnings) {
        jdbc.update("""
                UPDATE catalog_import_jobs
                SET status = 'FAILED', finished_at = now(), errors = CAST(? AS jsonb),
                    warnings = CAST(? AS jsonb)
                WHERE id = ?
                """, writeJson(errors), writeJson(warnings), id);
    }

    private RowMapper<CatalogImportJob> mapper() {
        return (rs, row) -> new CatalogImportJob(
                rs.getLong("id"),
                rs.getString("job_type"),
                ImportJobStatus.valueOf(rs.getString("status")),
                rs.getString("idempotency_key"),
                rs.getString("content_hash"),
                rs.getString("source_version"),
                rs.getString("requested_by"),
                rs.getString("vector_space"),
                (Long) rs.getObject("catalog_version_id"),
                rs.getString("version_status") == null ? null
                        : CatalogVersionStatus.valueOf(rs.getString("version_status")),
                rs.getString("embedding_status") == null ? null
                        : EmbeddingStatus.valueOf(rs.getString("embedding_status")),
                rs.getInt("total_products"),
                rs.getInt("imported_products"),
                rs.getInt("embedded_products"),
                readErrors(rs.getString("errors")),
                readErrors(rs.getString("warnings")),
                toInstant(rs.getTimestamp("created_at")),
                toInstant(rs.getTimestamp("started_at")),
                toInstant(rs.getTimestamp("finished_at")));
    }

    private List<CatalogImportError> readErrors(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<CatalogImportError>>() {
            });
        } catch (Exception ex) {
            throw new IllegalStateException("Не удалось прочитать ошибки задания: " + ex.getMessage(), ex);
        }
    }

    private String writeJson(List<CatalogImportError> value) {
        try {
            return objectMapper.writeValueAsString(value == null ? List.of() : value);
        } catch (Exception ex) {
            throw new IllegalStateException("Не удалось сериализовать ошибки задания: " + ex.getMessage(), ex);
        }
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
