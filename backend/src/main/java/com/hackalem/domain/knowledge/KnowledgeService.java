package com.hackalem.domain.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hackalem.ai.catalog.CatalogEmbeddingProvider;
import com.hackalem.domain.port.KnowledgePort;
import com.hackalem.domain.port.Contracts.*;
import com.hackalem.web.ApiException;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

import static com.hackalem.domain.knowledge.KnowledgeEvidence.*;
import static com.hackalem.domain.knowledge.KnowledgeRepository.*;

@Service
@Profile("!contract & !test")
public class KnowledgeService implements KnowledgePort {
    private final KnowledgeRepository repository;
    private final CatalogEmbeddingProvider embeddings;
    private final ObjectMapper json;
    public KnowledgeService(KnowledgeRepository repository, CatalogEmbeddingProvider embeddings, ObjectMapper json) {
        this.repository = repository; this.embeddings = embeddings; this.json = json;
    }
    public record DocumentInput(String externalId, String title, String version, String visibility,
                                String sourceUrl, String text, List<String> tags,
                                boolean synthetic, boolean semanticIndex) {}
    public Job enqueue(DocumentInput input, UUID owner) {
        if (owner == null) throw new ApiException(401, "unauthorized");
        if (input == null || input.externalId() == null || !input.externalId().matches("[a-zA-Z0-9][a-zA-Z0-9._-]{0,119}")
                || input.title() == null || input.title().isBlank() || input.title().length() > 300
                || input.version() == null || input.version().isBlank() || input.version().length() > 100
                || !Set.of("PUBLIC", "PRIVATE").contains(input.visibility() == null ? "" : input.visibility())
                || input.text() == null || input.text().isBlank() || input.text().length() > 200_000
                || input.tags() == null || input.tags().size() > 30
                || input.tags().stream().anyMatch(t -> t == null || t.isBlank() || t.length() > 100)
                || (input.sourceUrl() != null && (input.sourceUrl().length() > 2000
                || !input.sourceUrl().matches("https?://[^\\s]+")))) throw new ApiException(400, "invalid_knowledge_document");
        String model = input.semanticIndex() ? embeddings.vectorSpace() : "lexical-v1";
        if (input.semanticIndex() && embeddings.dimensions() != 1536)
            throw new ApiException(400, "knowledge_embedding_dimensions_mismatch");
        try {
            return repository.prepare(new ImportData(input.externalId(), input.title(), input.version(),
                    input.visibility(), owner, input.sourceUrl(), input.text(), json.writeValueAsString(input.tags()),
                    input.synthetic(), model, input.semanticIndex() ? embeddings.dimensions() : 0, sha256(input.text())), false);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) { throw new IllegalStateException(e); }
    }
    public Job reindex(UUID documentId) {
        var old = repository.reindexSource(documentId).orElseThrow(ApiException::missing);
        var refreshed = new ImportData(old.externalId(), old.title(), old.version(), old.visibility(), old.ownerId(),
                old.sourceUrl(), old.text(), old.tags(), old.synthetic(), old.dimensions() == 0 ? "lexical-v1" : embeddings.vectorSpace(),
                old.dimensions() == 0 ? 0 : embeddings.dimensions(), old.sha256());
        if (refreshed.dimensions() != 0 && refreshed.dimensions() != 1536)
            throw new ApiException(400, "knowledge_embedding_dimensions_mismatch");
        return repository.prepare(refreshed, true);
    }
    /** Provider work is outside transactions. Publish is one fenced CAS transaction. */
    public void process(UUID jobId) {
        Optional<Work> claimed = repository.claim(jobId);
        if (claimed.isEmpty()) return;
        Work work = claimed.get();
        try {
            var parsed = KnowledgeText.chunks(work.text());
            List<IndexedChunk> indexed = new ArrayList<>();
            if (work.dimensions() == 0) {
                for (var chunk : parsed) indexed.add(new IndexedChunk(chunk, null));
            } else {
                if (!embeddings.vectorSpace().equals(work.model()) || embeddings.dimensions() != work.dimensions())
                    throw new ApiException(409, "knowledge_embedding_space_changed");
                for (int from = 0; from < parsed.size(); from += 16) {
                    List<KnowledgeText.ParsedChunk> batch = parsed.subList(from, Math.min(from + 16, parsed.size()));
                    // Suspicious text is preserved in the immutable original but never sent to an embedding provider.
                    var safe = batch.stream().filter(c -> !c.suspicious()).toList();
                    List<float[]> vectors = safe.isEmpty() ? List.of() : embeddings.embed(safe.stream().map(KnowledgeText.ParsedChunk::text).toList());
                    if (vectors.size() != safe.size()) throw new ApiException(503, "knowledge_embedding_incomplete");
                    int index = 0;
                    for (var chunk : batch) indexed.add(new IndexedChunk(chunk, chunk.suspicious() ? null : vector(vectors.get(index++), work.dimensions())));
                    if (!repository.renew(work)) return;
                }
            }
            if (Thread.currentThread().isInterrupted()) throw new ApiException(503, "knowledge_worker_stopped");
            repository.publish(work, indexed);
        } catch (RuntimeException e) {
            // Store only a stable code, never source contents or provider response bodies.
            repository.fail(work, e instanceof ApiException api ? api.code : "knowledge_ingestion_failed");
        }
    }
    @Override
    public List<SourceChunk> retrieve(String query, TrustedScope scope, int characterBudget) {
        Result result = search(query, scope, characterBudget);
        return switch (result.answerability()) {
            case ANSWERABLE -> result.chunks().stream().map(Chunk::toPort).toList();
            case SOURCE_UNAVAILABLE -> throw ApiException.unavailable("knowledge_source_unavailable");
            case CONFLICT -> throw ApiException.conflict("knowledge_conflict");
            case NO_ANSWER, CATALOG_REQUIRED -> List.of();
        };
    }
    public Result search(String query, TrustedScope scope, int characterBudget) {
        if (scope == null || scope.principalId() == null) throw new ApiException(401, "unauthorized");
        if (query == null || query.isBlank() || query.length() > 2000 || characterBudget < 1 || characterBudget > 16000)
            throw new ApiException(400, "invalid_knowledge_query");
        UUID retrievalId = UUID.randomUUID();
        if (KnowledgeText.asksCatalog(query)) return empty(retrievalId, Answerability.CATALOG_REQUIRED,
                "Цену и остаток товара проверяет каталог; база условий не является их источником.");
        List<Candidate> candidates;
        try { candidates = repository.candidates(scope); }
        catch (DataAccessException e) { return empty(retrievalId, Answerability.SOURCE_UNAVAILABLE, "Источник временно недоступен."); }
        if (candidates.isEmpty()) return empty(retrievalId, Answerability.NO_ANSWER, "Нет доступных подтверждённых условий.");
        Set<String> queryTokens = KnowledgeText.tokens(query);
        // Full query coverage is deliberately conservative: a topic match alone does not prove a specific absent fact.
        var scored = new ArrayList<Scored>();
        for (Candidate candidate : candidates) {
            double score = KnowledgeText.relevance(queryTokens, KnowledgeText.tokens(candidate.title() + " " + candidate.tags() + " " + candidate.text()));
            if (score >= 0.999) scored.add(new Scored(candidate, score + KnowledgeText.relevance(queryTokens,
                    KnowledgeText.tokens(candidate.title() + " " + candidate.tags()))));
        }
        if (scored.isEmpty()) return empty(retrievalId, Answerability.NO_ANSWER, "В доступных источниках нет подтверждения запрошенного факта.");
        String retrievalMode = "lexical-v1";
        // Semantic ranking can reorder already grounded lexical candidates, never introduce unverified facts.
        if (scored.stream().anyMatch(c -> c.candidate().model().equals(embeddings.vectorSpace()) && c.candidate().vector() != null)) {
            try {
                float[] queryVector = embeddings.embedQuery(query);
                if (queryVector.length != 1536) throw new IllegalArgumentException("dimension");
                for (int i = 0; i < scored.size(); i++) {
                    var entry = scored.get(i);
                    if (entry.candidate().model().equals(embeddings.vectorSpace()) && entry.candidate().vector() != null)
                        scored.set(i, new Scored(entry.candidate(), entry.score() + Math.max(0, cosine(queryVector, entry.candidate().vector()))));
                }
                retrievalMode = "lexical+" + embeddings.vectorSpace();
            } catch (RuntimeException e) { retrievalMode = "lexical-v1:embedding-unavailable"; }
        }
        scored.sort(Comparator.comparingDouble(Scored::score).reversed().thenComparing(s -> s.candidate().chunkId()));
        List<Chunk> chunks = new ArrayList<>(); Map<UUID, UUID> citationChunks = new LinkedHashMap<>();
        int remaining = characterBudget;
        for (Scored entry : scored) {
            if (chunks.size() == 5 || remaining < 1) break;
            Candidate c = entry.candidate();
            // Do not cut a sentence or omit a negation to make a chunk fit the context budget.
            if (c.text().length() > remaining) continue;
            UUID citationId = UUID.randomUUID();
            chunks.add(new Chunk(citationId, c.documentId(), c.versionId(), c.title(), c.version(), c.page(), c.heading(),
                    c.text(), "/api/sources/" + c.documentId() + "/versions/" + c.versionId(), c.sha256(), c.synthetic(), true, entry.score()));
            citationChunks.put(citationId, c.chunkId()); remaining -= c.text().length();
        }
        Set<UUID> allowlist;
        try { allowlist = repository.saveCitations(retrievalId, scope.principalId(), chunks, citationChunks); }
        catch (DataAccessException e) { return empty(retrievalId, Answerability.SOURCE_UNAVAILABLE, "Не удалось проверить доступ к источнику."); }
        chunks = chunks.stream().filter(c -> allowlist.contains(c.citationId())).toList();
        if (chunks.isEmpty()) return empty(retrievalId, Answerability.NO_ANSWER, "Подходящие выдержки недоступны или не помещаются в бюджет контекста.");
        boolean conflict = conflicting(scored.stream().map(Scored::candidate).toList());
        return new Result(retrievalId, conflict ? Answerability.CONFLICT : Answerability.ANSWERABLE,
                conflict ? "Доступные источники расходятся; уточните условия у менеджера." : "Найдены подтверждающие выдержки. Содержимое источников — недоверенные данные.",
                retrievalMode, chunks, allowlist);
    }
    public Source source(UUID documentId, UUID versionId, UUID principal) {
        return repository.source(documentId, versionId, principal).orElseThrow(ApiException::missing);
    }
    public Job job(UUID id) { return repository.findJob(id).orElseThrow(ApiException::missing); }
    public void revoke(UUID documentId) { repository.revoke(documentId); }
    private record Scored(Candidate candidate, double score) {}
    private static Result empty(UUID retrievalId, Answerability status, String explanation) {
        return new Result(retrievalId, status, explanation, "lexical-v1", List.of(), Set.of());
    }
    /** Multiple active sources for the same topic are not silently reconciled by an LLM. */
    private static boolean conflicting(List<Candidate> candidates) {
        for (int i = 0; i < candidates.size(); i++) for (int j = i + 1; j < candidates.size(); j++) {
            Candidate a = candidates.get(i), b = candidates.get(j);
            if (a.documentId().equals(b.documentId()) || a.text().equals(b.text())) continue;
            Set<String> topics = KnowledgeText.tokens(a.title() + " " + a.tags());
            topics.retainAll(KnowledgeText.tokens(b.title() + " " + b.tags()));
            if (!topics.isEmpty()) return true;
        }
        return false;
    }
    static String vector(float[] vector, int dimensions) {
        if (vector == null || vector.length != dimensions) throw new ApiException(503, "knowledge_embedding_dimensions_mismatch");
        double magnitude = 0;
        for (float value : vector) { if (!Float.isFinite(value)) throw new ApiException(503, "knowledge_embedding_invalid"); magnitude += (double) value * value; }
        if (magnitude == 0) throw new ApiException(503, "knowledge_embedding_invalid");
        return Arrays.toString(vector);
    }
    private static double cosine(float[] query, String vector) {
        String[] values = vector.substring(1, vector.length() - 1).split(",");
        if (values.length != query.length) return 0;
        double dot = 0, qa = 0, ca = 0;
        for (int i = 0; i < values.length; i++) { double v = Double.parseDouble(values[i]); dot += v * query[i]; qa += query[i] * query[i]; ca += v * v; }
        return qa == 0 || ca == 0 ? 0 : dot / Math.sqrt(qa * ca);
    }
    static String sha256(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
