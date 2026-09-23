package com.hackalem.web;
import static com.hackalem.domain.port.Contracts.*;
import com.hackalem.domain.chat.ChatService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;
@RestController
@RequestMapping("/api")
public class ChatController {
    private final ChatService chat;
    public ChatController(ChatService chat){this.chat=chat;}
    @PostMapping("/conversations") @ResponseStatus(HttpStatus.CREATED)
    public Conversation createConversation(@AuthenticationPrincipal TrustedScope scope){return chat.create(scope);}
    @GetMapping("/conversations")
    public ConversationPage listConversations(@AuthenticationPrincipal TrustedScope scope,@RequestParam(required=false) String cursor,@RequestParam(defaultValue="30") int limit){return chat.conversations(scope,cursor,limit);}
    @GetMapping("/conversations/{id}/messages")
    public MessagePage listMessages(@AuthenticationPrincipal TrustedScope scope,@PathVariable UUID id,@RequestParam(required=false) String cursor,@RequestParam(defaultValue="30") int limit){return chat.messages(scope,id,cursor,limit);}
    @PostMapping("/conversations/{id}/messages") @ResponseStatus(HttpStatus.ACCEPTED)
    public AcceptedTurn submitMessage(@AuthenticationPrincipal TrustedScope scope,@PathVariable UUID id,@RequestHeader("Idempotency-Key") String key,@Valid @RequestBody TurnRequest request){return chat.submit(scope,id,key,request);}
    @GetMapping("/runs/{id}") public RunSnapshot getRun(@AuthenticationPrincipal TrustedScope scope,@PathVariable UUID id){return chat.run(scope,id);}
    @PostMapping("/runs/{id}/cancel") public RunSnapshot cancelRun(@AuthenticationPrincipal TrustedScope scope,@PathVariable UUID id){return chat.cancel(scope,id);}
    @GetMapping("/conversations/{id}/state") public DialogueState getDialogue(@AuthenticationPrincipal TrustedScope scope,@PathVariable UUID id){return chat.state(scope,id);}
    @PatchMapping("/conversations/{id}/state") public DialogueState updateDialogue(@AuthenticationPrincipal TrustedScope scope,@PathVariable UUID id,@Valid @RequestBody UpdateDialogue request){return chat.updateState(scope,id,request);}
    @GetMapping("/conversations/{id}/results/{resultId}") public ProductResultSet getResultSet(@AuthenticationPrincipal TrustedScope scope,@PathVariable UUID id,@PathVariable UUID resultId){return chat.resultSet(scope,id,resultId);}
}
