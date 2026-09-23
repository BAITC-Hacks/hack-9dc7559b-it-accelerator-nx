package com.hackalem.domain.knowledge;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;
import java.io.InputStream;
import java.util.*;
@Component @Profile("!contract & !test")
public class KnowledgeSeed implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(KnowledgeSeed.class);
    private static final UUID SEED_OWNER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private final KnowledgeService service; private final KnowledgeRepository repository;
    private final ObjectMapper json; private final ResourceLoader resources;
    private final boolean enabled; private final String location;
    public KnowledgeSeed(KnowledgeService service, KnowledgeRepository repository, ObjectMapper json, ResourceLoader resources,
                         @Value("${app.knowledge.seed-enabled:true}") boolean enabled,
                         @Value("${app.knowledge.seed-location:file:../data/purchase_terms/terms.json}") String location) {
        this.service = service; this.repository = repository; this.json = json; this.resources = resources;
        this.enabled = enabled; this.location = location;
    }
    @Override public void run(ApplicationArguments args) throws Exception {
        if (!enabled) return;
        var resource = resources.getResource(location);
        if (!resource.exists()) { log.warn("Synthetic knowledge fixture is unavailable; seed skipped"); return; }
        try (InputStream input = resource.getInputStream()) {
            var fixture = json.readTree(input);
            if (fixture.path("schemaVersion").asInt() != 1 || !fixture.path("synthetic").asBoolean())
                throw new IllegalArgumentException("Knowledge seed must be explicitly synthetic schema v1");
            for (var document : fixture.path("documents")) {
                // Restart must not resurrect deleted sources or overwrite administrator changes.
                String id = document.path("id").asText();
                if (repository.exists(id)) continue;
                if (!document.path("visibility").asText().equals("PUBLIC"))
                    throw new IllegalArgumentException("Shared seed must contain public documents only");
                List<String> tags = new ArrayList<>(); document.path("tags").forEach(t -> tags.add(t.asText()));
                var job = service.enqueue(new KnowledgeService.DocumentInput(id, document.path("title").asText(),
                        document.path("version").asText(), "PUBLIC", document.path("sourceUrl").asText(),
                        document.path("text").asText(), tags, true, false), SEED_OWNER);
                service.process(job.id());
            }
        }
    }
}
