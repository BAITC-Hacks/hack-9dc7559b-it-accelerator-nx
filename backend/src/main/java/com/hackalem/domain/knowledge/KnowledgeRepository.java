package com.hackalem.domain.knowledge;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.context.annotation.Profile;
import com.hackalem.domain.port.Contracts.TrustedScope;
import com.hackalem.web.ApiException;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

@Repository
@Profile("!contract & !test")
public class KnowledgeRepository {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    public KnowledgeRepository(JdbcTemplate jdbc, TransactionTemplate tx) { this.jdbc = jdbc; this.tx = tx; }
    public record ImportData(String externalId, String title, String version, String visibility,
                             UUID ownerId, String sourceUrl, String text, String tags,
                             boolean synthetic, String model, int dimensions, String sha256) {}
    public record Job(UUID id, UUID documentId, UUID versionId, String state, String errorCode, long epoch) {}
    public record Work(Job job, String text, String model, int dimensions) {}
    public record IndexedChunk(KnowledgeText.ParsedChunk chunk, String vector) {}
    public record Candidate(UUID chunkId, UUID documentId, UUID versionId, String sourceKey, String title, String version,
                            String tags, int page, String heading, String text, String sha256,
                            boolean synthetic, String model, String vector) {}
    public record ReindexSource(ImportData document, UUID versionId) {}
    public record Source(UUID documentId, UUID versionId, String title, String version,
                         String text, String sha256, boolean synthetic) {}

    public boolean exists(String externalId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM documents WHERE external_id=?)", Boolean.class, externalId));
    }
    public Job prepare(ImportData input, boolean forceReindex) {
        return prepare(input, forceReindex, null);
    }
    public Job prepareReindex(ImportData input, UUID expectedActiveVersion) {
        return prepare(input, true, Objects.requireNonNull(expectedActiveVersion));
    }
    private Job prepare(ImportData input, boolean forceReindex, UUID expectedActiveVersion) {
        return tx.execute(status -> {
            UUID docId = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO documents(id,external_id,title,visibility,owner_id) VALUES (?,?,?,?,?)
                    ON CONFLICT(external_id) DO NOTHING
                    """, docId, input.externalId(), input.title(), input.visibility(), input.ownerId());
            docId = jdbc.queryForObject("SELECT id FROM documents WHERE external_id=? FOR UPDATE", UUID.class, input.externalId());
            if (expectedActiveVersion != null && !Boolean.TRUE.equals(jdbc.queryForObject("""
                    SELECT NOT tombstoned AND active_version_id=? AND desired_version_id=?
                    FROM documents WHERE id=?
                    """, Boolean.class, expectedActiveVersion, expectedActiveVersion, docId)))
                throw ApiException.conflict("knowledge_reindex_stale");
            if (!forceReindex) {
                List<Job> existing = jdbc.query("""
                    SELECT j.* FROM ingestion_jobs j JOIN document_versions v ON v.id=j.version_id
                    JOIN documents d ON d.desired_version_id=v.id
                    WHERE d.id=? AND NOT d.tombstoned AND v.source_sha256=? AND v.version_label=?
                      AND v.embedding_model=? AND v.title=? AND v.tags=?
                      AND COALESCE(v.source_url,'')=COALESCE(?,'') AND v.synthetic=?
                      AND d.visibility=? AND d.owner_id=? AND j.state IN ('QUEUED','RUNNING','SUCCEEDED')
                    """, this::job, docId, input.sha256(), input.version(), input.model(), input.title(),
                        input.tags(), input.sourceUrl(), input.synthetic(), input.visibility(), input.ownerId());
                if (!existing.isEmpty()) return existing.getFirst();
            }
            UUID versionId = UUID.randomUUID();
            UUID jobId = UUID.randomUUID();
            jdbc.update("""
                INSERT INTO document_versions(id,document_id,version_label,title,source_object_key,source_sha256,
                    source_url,original_text,tags,synthetic,embedding_model,embedding_dimensions,state)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,'PENDING')
                """, versionId, docId, input.version(), input.title(), "db:document_versions/" + versionId,
                    input.sha256(), input.sourceUrl(), input.text(), input.tags(), input.synthetic(), input.model(), input.dimensions());
            jdbc.update("""
                UPDATE documents SET title=?,visibility=?,owner_id=?,desired_version_id=?,tombstoned=FALSE,
                    updated_at=now() WHERE id=?
                """, input.title(), input.visibility(), input.ownerId(), versionId, docId);
            jdbc.update("INSERT INTO ingestion_jobs(id,document_id,version_id,state) VALUES (?,?,?,'QUEUED')", jobId, docId, versionId);
            return new Job(jobId, docId, versionId, "QUEUED", null, 0);
        });
    }
    public List<UUID> recoverableJobs() {
        return jdbc.query("""
                SELECT id FROM ingestion_jobs WHERE state='QUEUED' OR
                    (state='RUNNING' AND lease_until < now() AND attempts<3) ORDER BY created_at LIMIT 2
                """, (rs, row) -> rs.getObject(1, UUID.class));
    }
    public Optional<Work> claim(UUID jobId) {
        return tx.execute(status -> {
            int changed = jdbc.update("""
                    UPDATE ingestion_jobs SET state='RUNNING',lease_epoch=lease_epoch+1,attempts=attempts+1,
                        lease_until=now()+INTERVAL '120 seconds',updated_at=now()
                    WHERE id=? AND attempts<3 AND (state='QUEUED' OR (state='RUNNING' AND lease_until < now()))
                    """, jobId);
            if (changed == 0) return Optional.empty();
            return Optional.of(jdbc.queryForObject("""
                    SELECT j.*,v.original_text,v.embedding_model,v.embedding_dimensions
                    FROM ingestion_jobs j JOIN document_versions v ON v.id=j.version_id WHERE j.id=?
                    """, (rs, row) -> new Work(job(rs, row), rs.getString("original_text"),
                    rs.getString("embedding_model"), rs.getInt("embedding_dimensions")), jobId));
        });
    }
    public void publish(Work work, List<IndexedChunk> chunks) {
        tx.executeWithoutResult(status -> {
            // Lock document first in both prepare/publish; job fencing rejects stale workers.
            jdbc.queryForObject("SELECT id FROM documents WHERE id=? FOR UPDATE", UUID.class, work.job().documentId());
            List<UUID> valid = jdbc.query("""
                    SELECT id FROM ingestion_jobs WHERE id=? AND state='RUNNING'
                        AND lease_epoch=? AND lease_until > now() FOR UPDATE
                    """, (rs, n) -> rs.getObject(1, UUID.class), work.job().id(), work.job().epoch());
            if (valid.isEmpty()) return;
            int published = jdbc.update("""
                    UPDATE documents SET active_version_id=?,updated_at=now()
                    WHERE id=? AND desired_version_id=? AND NOT tombstoned
                    """, work.job().versionId(), work.job().documentId(), work.job().versionId());
            if (published == 0) {
                jdbc.update("UPDATE ingestion_jobs SET state='SUPERSEDED',lease_until=NULL,updated_at=now() WHERE id=?", work.job().id());
                jdbc.update("UPDATE document_versions SET state='FAILED' WHERE id=?", work.job().versionId());
                return;
            }
            jdbc.update("DELETE FROM document_chunks WHERE version_id=?", work.job().versionId());
            for (IndexedChunk indexed : chunks) {
                var c = indexed.chunk();
                jdbc.update("""
                        INSERT INTO document_chunks(id,document_id,version_id,ordinal,page_number,heading,content,suspicious,embedding)
                        VALUES (?,?,?,?,?,?,?,?,CAST(? AS vector))
                        """, UUID.randomUUID(), work.job().documentId(), work.job().versionId(), c.ordinal(),
                        c.page(), c.heading(), c.text(), c.suspicious(), indexed.vector());
            }
            // Old originals/chunks remain addressable for citations; their unused vectors can be reclaimed.
            jdbc.update("UPDATE document_chunks SET embedding=NULL WHERE document_id=? AND version_id<>?", work.job().documentId(), work.job().versionId());
            jdbc.update("UPDATE document_versions SET state='READY' WHERE id=?", work.job().versionId());
            jdbc.update("UPDATE ingestion_jobs SET state='SUCCEEDED',lease_until=NULL,updated_at=now() WHERE id=?", work.job().id());
        });
    }
    public void fail(Work work, String code) {
        tx.executeWithoutResult(status -> {
            int changed = jdbc.update("""
                    UPDATE ingestion_jobs SET state='FAILED',error_code=?,lease_until=NULL,updated_at=now()
                    WHERE id=? AND lease_epoch=? AND state='RUNNING' AND lease_until > now()
                    """, code, work.job().id(), work.job().epoch());
            if (changed > 0) jdbc.update("UPDATE document_versions SET state='FAILED' WHERE id=?", work.job().versionId());
        });
    }
    public boolean renew(Work work) {
        return jdbc.update("""
                UPDATE ingestion_jobs SET lease_until=now()+INTERVAL '120 seconds',updated_at=now()
                WHERE id=? AND state='RUNNING' AND lease_epoch=? AND lease_until>now()
                """, work.job().id(), work.job().epoch()) == 1;
    }
    public void expireExhaustedJobs() {
        jdbc.update("""
                UPDATE ingestion_jobs SET state='FAILED',error_code='lease_attempts_exhausted',lease_until=NULL
                WHERE state='RUNNING' AND lease_until<now() AND attempts>=3
                """);
    }
    public Optional<Job> findJob(UUID id) {
        return jdbc.query("SELECT * FROM ingestion_jobs WHERE id=?", this::job, id).stream().findFirst();
    }
    public List<Candidate> candidates(TrustedScope scope) {
        String sql = """
                SELECT c.id AS chunk_id,c.document_id,c.version_id,d.external_id,v.title,v.version_label,v.tags,
                       c.page_number,c.heading,c.content,v.source_sha256,v.synthetic,v.embedding_model,
                       CAST(c.embedding AS text) AS vector_text
                FROM document_chunks c JOIN documents d ON d.id=c.document_id
                JOIN document_versions v ON v.id=c.version_id
                WHERE NOT d.tombstoned AND d.active_version_id=v.id AND v.state='READY'
                  AND NOT c.suspicious AND (d.visibility='PUBLIC' OR d.owner_id=?)
                """;
        List<Object> args = new ArrayList<>(); args.add(scope.principalId());
        // A per-request scan cap bounds the deterministic fallback, not the corpus size.
        sql += " ORDER BY c.document_id,c.ordinal LIMIT 2000";
        return jdbc.query(sql, (rs, row) -> new Candidate(rs.getObject("chunk_id", UUID.class),
                rs.getObject("document_id", UUID.class), rs.getObject("version_id", UUID.class),
                rs.getString("external_id"), rs.getString("title"), rs.getString("version_label"), rs.getString("tags"),
                rs.getInt("page_number"), rs.getString("heading"), rs.getString("content"),
                rs.getString("source_sha256"), rs.getBoolean("synthetic"), rs.getString("embedding_model"),
                rs.getString("vector_text")), args.toArray());
    }
    public Optional<Source> source(UUID docId, UUID versionId, UUID principal) {
        return jdbc.query("""
                SELECT v.* FROM documents d JOIN document_versions v ON v.document_id=d.id
                WHERE d.id=? AND v.id=? AND NOT d.tombstoned AND v.state='READY'
                  AND (d.visibility='PUBLIC' OR d.owner_id=?)
                """, (rs, row) -> new Source(docId, versionId, rs.getString("title"), rs.getString("version_label"),
                rs.getString("original_text"), rs.getString("source_sha256"), rs.getBoolean("synthetic")),
                docId, versionId, principal).stream().findFirst();
    }
    public Optional<ReindexSource> reindexSource(UUID documentId) {
        return jdbc.query("""
                SELECT d.external_id,d.visibility,d.owner_id,v.* FROM documents d
                JOIN document_versions v ON v.id=d.active_version_id WHERE d.id=? AND NOT d.tombstoned
                """, (rs, row) -> new ReindexSource(new ImportData(rs.getString("external_id"), rs.getString("title"),
                rs.getString("version_label"), rs.getString("visibility"), rs.getObject("owner_id", UUID.class), rs.getString("source_url"),
                rs.getString("original_text"), rs.getString("tags"), rs.getBoolean("synthetic"),
                rs.getString("embedding_model"), rs.getInt("embedding_dimensions"), rs.getString("source_sha256")),
                rs.getObject("id", UUID.class)), documentId).stream().findFirst();
    }
    public void revoke(UUID documentId) {
        tx.executeWithoutResult(status -> {
            jdbc.update("UPDATE documents SET tombstoned=TRUE,active_version_id=NULL,desired_version_id=NULL,updated_at=now() WHERE id=?", documentId);
            jdbc.update("UPDATE ingestion_jobs SET state='SUPERSEDED',lease_epoch=lease_epoch+1,lease_until=NULL WHERE document_id=? AND state IN ('QUEUED','RUNNING')", documentId);
            jdbc.update("UPDATE document_chunks SET embedding=NULL WHERE document_id=?", documentId);
        });
    }
    public Set<UUID> saveCitations(UUID retrievalId, UUID principal, List<KnowledgeEvidence.Chunk> chunks,
                                  Map<UUID, UUID> citationChunks) {
        return tx.execute(status -> {
            Set<UUID> allowed = new LinkedHashSet<>();
            for (var chunk : chunks) {
                // Recheck current ACL and active version before including evidence in the response.
                int saved = jdbc.update("""
                        INSERT INTO message_citations(id,principal_id,chunk_id,version_id,retrieval_id)
                        SELECT ?,?,?,?,? FROM documents d WHERE d.id=? AND NOT d.tombstoned
                          AND d.active_version_id=? AND (d.visibility='PUBLIC' OR d.owner_id=?)
                        """, chunk.citationId(), principal, citationChunks.get(chunk.citationId()),
                        chunk.versionId(), retrievalId, chunk.documentId(), chunk.versionId(), principal);
                if (saved > 0) allowed.add(chunk.citationId());
            }
            return allowed;
        });
    }
    private Job job(ResultSet rs, int row) throws SQLException {
        return new Job(rs.getObject("id", UUID.class), rs.getObject("document_id", UUID.class),
                rs.getObject("version_id", UUID.class), rs.getString("state"), rs.getString("error_code"), rs.getLong("lease_epoch"));
    }
}
