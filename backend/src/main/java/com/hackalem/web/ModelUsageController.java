package com.hackalem.web;

import com.hackalem.ai.agent.ModelUsage;
import com.hackalem.domain.port.Contracts.TrustedScope;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.UUID;

@RestController
public class ModelUsageController {
    private final ModelUsage usage;
    public ModelUsageController(ModelUsage usage) { this.usage=usage; }
    @GetMapping("/api/runs/{id}/usage")
    public List<ModelUsage.Usage> getModelUsage(@AuthenticationPrincipal TrustedScope scope,@PathVariable UUID id) {
        return usage.read(scope,id);
    }
}
