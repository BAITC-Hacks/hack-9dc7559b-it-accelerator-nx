package com.hackalem.domain.port;
import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;
public interface LlmGateway {
    record ToolSpec(String name,String description,String schema) {}
    record ToolCall(String id,String name,String arguments) {}
    record Turn(String role,String text,List<ToolCall> calls,String callId,String toolName) {}
    record Round(String text,List<ToolCall> calls,int inputTokens,int outputTokens,String model,String requestId) {}
    Round stream(List<Turn> context,List<ToolSpec> tools,int maxOutputTokens,Instant deadline,Consumer<String> text);
}
