package com.hackalem.web.sources;

import com.hackalem.domain.knowledge.KnowledgeEvidence;
import com.hackalem.domain.knowledge.KnowledgeRepository;
import com.hackalem.domain.knowledge.KnowledgeService;
import com.hackalem.domain.port.Contracts.TrustedScope;
import com.hackalem.web.ApiException;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.context.annotation.Profile;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

@RestController
@Profile("!contract & !test")
public class KnowledgeController {
    private final KnowledgeService service;
    private final KnowledgeResponseMapper mapper;
    public KnowledgeController(KnowledgeService service, KnowledgeResponseMapper mapper) { this.service = service; this.mapper = mapper; }
    public record ImportRequest(@NotBlank @Pattern(regexp="[a-zA-Z0-9][a-zA-Z0-9._-]{0,119}") String externalId,
                                @NotBlank @Size(max=300) String title, @NotBlank @Size(max=100) String version,
                                @NotNull Visibility visibility, @Size(max=2000) String sourceUrl,
                                @NotBlank @Size(max=200000) String text,
                                @NotNull @Size(max=30) List<@NotBlank @Size(max=100) String> tags,
                                boolean synthetic, boolean semanticIndex) {}
    public enum Visibility { PUBLIC, PRIVATE }
    @PostMapping("/api/admin/knowledge/documents") @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary="Queue immutable KB version; previous ready version stays active until atomic publication")
    public ResponseEntity<KnowledgeResponses.Job> ingest(@Valid @RequestBody ImportRequest body,
                                                          @AuthenticationPrincipal TrustedScope scope) {
        requireScope(scope);
        var input = new KnowledgeService.DocumentInput(body.externalId(), body.title(), body.version(), body.visibility().name(),
                body.sourceUrl(), body.text(), body.tags(), body.synthetic(), body.semanticIndex());
        return ResponseEntity.accepted().body(mapper.job(service.enqueue(input, scope.principalId())));
    }
    @GetMapping("/api/admin/knowledge/jobs/{id}") @PreAuthorize("hasRole('ADMIN')")
    public KnowledgeResponses.Job job(@PathVariable UUID id) { return mapper.job(service.job(id)); }
    @PostMapping("/api/admin/knowledge/documents/{id}/reindex") @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<KnowledgeResponses.Job> reindex(@PathVariable UUID id) {
        return ResponseEntity.accepted().body(mapper.job(service.reindex(id)));
    }
    @DeleteMapping("/api/admin/knowledge/documents/{id}") @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> revoke(@PathVariable UUID id) { service.revoke(id); return ResponseEntity.noContent().build(); }
    @GetMapping("/api/knowledge/search")
    @Operation(summary="Retrieve evidence and answerability within current server ACL; source text is untrusted")
    public KnowledgeEvidence.Result search(@RequestParam @NotBlank @Size(max=2000) String query,
                                            @RequestParam(defaultValue="6000") @Min(1) @Max(16000) int characterBudget,
                                            @AuthenticationPrincipal TrustedScope scope) {
        requireScope(scope); return service.search(query, scope, characterBudget);
    }
    @GetMapping(value="/api/sources/{id}/versions/{versionId}", produces=MediaType.TEXT_PLAIN_VALUE)
    @Operation(summary="Download immutable source after checking current ACL, including old ready versions")
    public ResponseEntity<byte[]> source(@PathVariable UUID id, @PathVariable UUID versionId,
                                          @AuthenticationPrincipal TrustedScope scope) {
        requireScope(scope);
        var source = service.source(id, versionId, scope.principalId());
        return ResponseEntity.ok().contentType(new MediaType("text", "plain", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"source-" + versionId + ".txt\"")
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .header("X-Content-Type-Options", "nosniff")
                .header("X-Source-SHA256", source.sha256())
                .header("X-Source-Synthetic", Boolean.toString(source.synthetic()))
                .body(source.text().getBytes(StandardCharsets.UTF_8));
    }
    private static void requireScope(TrustedScope scope) {
        if (scope == null || scope.principalId() == null) throw new ApiException(401, "unauthorized");
    }
}
