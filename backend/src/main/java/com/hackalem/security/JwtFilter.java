package com.hackalem.security;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hackalem.web.ApiErrors;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.util.List;
@Component
public class JwtFilter extends OncePerRequestFilter {
    private final SessionService sessions;
    private final ObjectMapper json;
    private final java.util.Set<String> adminPrincipals;
    public JwtFilter(SessionService sessions,ObjectMapper json,@org.springframework.beans.factory.annotation.Value("${app.admin-principal-ids:}") String admins) {
        this.sessions=sessions; this.json=json;
        this.adminPrincipals=java.util.Arrays.stream(admins.split(",")).map(String::trim).filter(s->!s.isEmpty()).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
    @Override protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain) throws IOException,ServletException {
        String auth=req.getHeader("Authorization");
        if(auth!=null) {
            try {
                if(!auth.startsWith("Bearer ")) throw new IllegalArgumentException();
                var scope=sessions.verify(auth.substring(7));
                var authorities=new java.util.ArrayList<SimpleGrantedAuthority>();
                authorities.add(new SimpleGrantedAuthority("ROLE_VISITOR"));
                if(adminPrincipals.contains(scope.principalId().toString()))authorities.add(new SimpleGrantedAuthority("ROLE_ADMIN"));
                SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(scope,null,
                    authorities));
            } catch(io.jsonwebtoken.JwtException | IllegalArgumentException | com.hackalem.web.ApiException e) {
                res.setStatus(401); res.setContentType("application/problem+json");
                json.writeValue(res.getOutputStream(),ApiErrors.problem(401,"invalid_token").getBody()); return;
            }
        }
        chain.doFilter(req,res);
    }
}
