package com.hackalem.ai.agent;

import com.hackalem.domain.chat.ChatService;
import com.hackalem.domain.port.Contracts.TrustedScope;
import com.hackalem.domain.port.LlmGateway.Round;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.UUID;

/** Per-run QA evidence, without prompts, credentials or document contents. */
@Service
public class ModelUsage {
    private final JdbcTemplate db;
    private final ChatService chat;
    public ModelUsage(JdbcTemplate db, ChatService chat) { this.db=db; this.chat=chat; }
    public record Usage(String epoch, int round, String model, int inputTokens, int outputTokens,
                        boolean measured, String promptVersion) {}
    public void record(ChatService.Claim claim, int round, Round response) {
        db.update("INSERT INTO agent_model_usage(run_id,epoch,round,model,input_tokens,output_tokens,measured) VALUES (?,?,?,?,?,?,?) ON CONFLICT DO NOTHING",
                claim.id(),claim.epoch(),round,response.model(),response.inputTokens(),response.outputTokens(),
                response.inputTokens()+response.outputTokens()>0);
    }
    public List<Usage> read(TrustedScope scope, UUID runId) {
        chat.run(scope,runId); // Same owner/active-session boundary as the run itself.
        return db.query("SELECT * FROM agent_model_usage WHERE run_id=? ORDER BY epoch,round",
                (r,n)->new Usage(r.getString("epoch"),r.getInt("round"),r.getString("model"),
                        r.getInt("input_tokens"),r.getInt("output_tokens"),r.getBoolean("measured"),"ekt-agent-v1"),runId);
    }
}
