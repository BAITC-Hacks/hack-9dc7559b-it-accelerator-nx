package com.hackalem.web.sources;
import java.util.UUID;
public final class KnowledgeResponses {
    private KnowledgeResponses() {}
    public record Job(UUID id, UUID documentId, UUID versionId, String state, String errorCode, String epoch) {}
}
