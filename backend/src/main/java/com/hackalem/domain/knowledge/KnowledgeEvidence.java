package com.hackalem.domain.knowledge;

import com.hackalem.domain.port.Contracts.SourceChunk;
import com.hackalem.domain.port.Contracts.SourceRef;
import java.util.*;

/** Explicit answerability and retrieval allowlist; snippets are evidence, never instructions. */
public final class KnowledgeEvidence {
    private KnowledgeEvidence() {}
    public enum Answerability { ANSWERABLE, NO_ANSWER, CONFLICT, SOURCE_UNAVAILABLE, CATALOG_REQUIRED }
    public record Chunk(UUID citationId, UUID documentId, UUID versionId, String title,
                        String versionLabel, int page, String heading, String text, String sourcePath,
                        String sha256, boolean synthetic, boolean untrusted, double score) {
        public SourceChunk toPort() {
            return new SourceChunk(new SourceRef(documentId.toString(), versionId.toString(),
                    title + " (v" + versionLabel + (synthetic ? ", synthetic" : "") + ")", page, null, null), text);
        }
    }
    public record Result(UUID retrievalId, Answerability answerability, String explanation,
                         String retrievalMode, List<Chunk> chunks, Set<UUID> citationAllowlist) {
        public Result { chunks = List.copyOf(chunks); citationAllowlist = Set.copyOf(citationAllowlist); }
    }
}
