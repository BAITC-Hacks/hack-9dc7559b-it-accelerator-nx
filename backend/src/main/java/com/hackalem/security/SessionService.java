package com.hackalem.security;

import static com.hackalem.domain.port.Contracts.*;
import com.hackalem.web.ApiException;
import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

@Service
public class SessionService {
    private final JdbcTemplate db;
    private final SecretKey key;
    private final long ttl;
    public SessionService(JdbcTemplate db,@Value("${app.jwt.secret}") String secret,
                          @Value("${app.jwt.ttl-minutes}") long ttl) {
        this.db=db; this.key=Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8)); this.ttl=ttl;
    }
    @Transactional
    public SessionToken create() {
        UUID owner=UUID.randomUUID(), cart=UUID.randomUUID();
        Instant expires=Instant.now().plusSeconds(ttl*60);
        db.update("INSERT INTO visitor_sessions(id,cart_id,expires_at) VALUES (?,?,?)",owner,cart,java.sql.Timestamp.from(expires));
        db.update("INSERT INTO carts(id,owner_id) VALUES (?,?)",cart,owner);
        return token(owner,cart,expires);
    }
    private SessionToken token(UUID owner,UUID cart,Instant expires) {
        String jwt=Jwts.builder().issuer("hackalem").subject(owner.toString()).audience().add("ekt-widget").and()
            .issuedAt(new Date()).expiration(Date.from(expires)).signWith(key).compact();
        return new SessionToken(jwt,expires,owner.toString(),cart.toString());
    }
    public TrustedScope verify(String token) {
        var claims=Jwts.parser().verifyWith(key).requireIssuer("hackalem").requireAudience("ekt-widget")
            .build().parseSignedClaims(token).getPayload();
        if (claims.getExpiration()==null) throw new ApiException(401,"invalid_token");
        UUID owner=UUID.fromString(claims.getSubject());
        return db.query("SELECT cart_id FROM visitor_sessions WHERE id=? AND NOT revoked AND expires_at>now()",
            (rs,n)->new TrustedScope(owner,rs.getObject(1,UUID.class)),owner).stream().findFirst()
            .orElseThrow(()->new ApiException(401,"session_expired"));
    }
    /** Reload can reuse the token; refresh rotates expiry without changing cart or owner. */
    @Transactional
    public SessionToken refresh(TrustedScope scope) {
        Instant expires=Instant.now().plusSeconds(ttl*60);
        if(db.update("UPDATE visitor_sessions SET expires_at=? WHERE id=? AND NOT revoked AND expires_at>now()",
            java.sql.Timestamp.from(expires),scope.principalId())!=1) throw new ApiException(401,"session_expired");
        return token(scope.principalId(),scope.cartId(),expires);
    }
    public void revoke(TrustedScope scope) { db.update("UPDATE visitor_sessions SET revoked=true WHERE id=?",scope.principalId()); }
}
