package com.hackalem.domain.port;
import static com.hackalem.domain.port.Contracts.*;
import java.util.List;
public interface KnowledgePort { List<SourceChunk> retrieve(String query, TrustedScope scope, int characterBudget); }
