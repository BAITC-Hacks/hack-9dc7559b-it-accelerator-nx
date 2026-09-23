package com.hackalem.ai.gateway;
import com.hackalem.domain.port.LlmGateway;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import java.time.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.concurrent.atomic.AtomicReference;

/** Spring AI 1.0.0 manual tool continuation; automatic execution/retries are disabled. */
@Component @Profile("!contract & !test")
public class OpenAiGateway implements LlmGateway {
    private final ChatModel model;
    public OpenAiGateway(ChatModel model){this.model=model;}
    public Round stream(List<Turn> context,List<ToolSpec> specs,int maxTokens,Instant deadline,Consumer<String> sink){
        List<Message> messages=new ArrayList<>();
        for(Turn turn:context)switch(turn.role()){
            case "system"->messages.add(new SystemMessage(turn.text()));
            case "user"->messages.add(new UserMessage(turn.text()));
            case "assistant"->messages.add(new AssistantMessage(turn.text(),Map.of(),turn.calls().stream().map(c->new AssistantMessage.ToolCall(c.id(),"function",c.name(),c.arguments())).toList()));
            case "tool"->messages.add(new ToolResponseMessage(List.of(new ToolResponseMessage.ToolResponse(turn.callId(),turn.toolName(),turn.text()))));
            default->throw new IllegalArgumentException("Unsupported role");
        }
        var callbacks=specs.stream().map(spec->(ToolCallback)new ToolCallback(){
            public ToolDefinition getToolDefinition(){return ToolDefinition.builder().name(spec.name()).description(spec.description()).inputSchema(spec.schema()).build();}
            public String call(String args){throw new IllegalStateException("Automatic tools are disabled");}
        }).toList();
        var options=ToolCallingChatOptions.builder().internalToolExecutionEnabled(false).toolCallbacks(callbacks).maxTokens(maxTokens).build();
        var text=new StringBuilder();Map<String,ToolCall> calls=new LinkedHashMap<>();
        AtomicReference<org.springframework.ai.chat.metadata.ChatResponseMetadata> metadata=new AtomicReference<>();
        // block() is interruptible; worker cancellation disposes the reactive HTTP subscription.
        model.stream(new Prompt(messages,options)).doOnNext(response->{
            metadata.set(response.getMetadata());
            if(response.getResult()==null)return;
            var output=response.getResult().getOutput();
            if(output.getText()!=null){text.append(output.getText());sink.accept(output.getText());}
            // Spring AI aggregates streamed tool arguments before exposing a complete ToolCall.
            for(var c:output.getToolCalls())calls.put(c.id(),new ToolCall(c.id(),c.name(),c.arguments()));
        }).then().block(Duration.between(Instant.now(),deadline));
        var meta=metadata.get();var usage=meta==null?null:meta.getUsage();
        return new Round(text.toString(),List.copyOf(calls.values()),usage==null?0:usage.getPromptTokens(),usage==null?0:usage.getCompletionTokens(),meta==null?null:meta.getModel(),meta==null?null:meta.getId());
    }
}
