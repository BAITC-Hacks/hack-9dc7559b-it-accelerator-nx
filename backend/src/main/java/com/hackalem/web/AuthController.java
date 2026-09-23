package com.hackalem.web;
import static com.hackalem.domain.port.Contracts.*;
import com.hackalem.security.SessionService;
import com.hackalem.ai.agent.Limits;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
@RestController
public class AuthController {
    private final SessionService sessions; private final Limits limits; private final boolean visitorEnabled;
    public AuthController(SessionService sessions,Limits limits,@Value("${app.visitor-enabled}") boolean enabled) {
        this.sessions=sessions;this.limits=limits;this.visitorEnabled=enabled;
    }
    @PostMapping("/auth/visitor-session")
    public SessionToken visitor(HttpServletRequest request) {
        if(!visitorEnabled) throw ApiException.unavailable("partner_identity_required");
        // Forwarded headers are deliberately not trusted as an unauthenticated identity.
        limits.rate("visitor",request.getRemoteAddr(),20);return sessions.create();
    }
    @PostMapping("/auth/refresh")
    public SessionToken refresh(@AuthenticationPrincipal TrustedScope scope) {limits.rate("refresh",scope.principalId().toString(),10);return sessions.refresh(scope);}
    @PostMapping("/auth/logout")
    public void logout(@AuthenticationPrincipal TrustedScope scope) {sessions.revoke(scope);}
}
