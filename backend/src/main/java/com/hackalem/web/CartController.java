package com.hackalem.web;
import static com.hackalem.domain.port.Contracts.*;
import com.hackalem.domain.cart.CartService;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;
@RestController @RequestMapping("/api/cart")
public class CartController {
    private final CartService cart;public CartController(CartService cart){this.cart=cart;}
    @GetMapping public CartSnapshot getCart(@AuthenticationPrincipal TrustedScope scope){return cart.cart(scope);}
    @PostMapping("/proposals") public ProposalSnapshot prepareProposal(@AuthenticationPrincipal TrustedScope scope,@RequestHeader("Idempotency-Key") String key,@Valid @RequestBody ProposalRequest request){return cart.propose(scope,request,key);}
    @GetMapping("/proposals/{id}") public ProposalSnapshot getProposal(@AuthenticationPrincipal TrustedScope scope,@PathVariable UUID id){return cart.get(scope,id);}
    @PostMapping("/proposals/{id}/confirm") public OperationOutcome confirmProposal(@AuthenticationPrincipal TrustedScope scope,@PathVariable UUID id,@RequestHeader("Idempotency-Key") String key,@Valid @RequestBody ConfirmRequest request){return cart.confirm(scope,id,key,request);}
    @PostMapping("/proposals/{id}/reject") public ProposalSnapshot rejectProposal(@AuthenticationPrincipal TrustedScope scope,@PathVariable UUID id){return cart.reject(scope,id);}
    @GetMapping("/operations/{id}") public OperationOutcome getCartOperation(@AuthenticationPrincipal TrustedScope scope,@PathVariable UUID id){return cart.operation(scope,id);}
}
