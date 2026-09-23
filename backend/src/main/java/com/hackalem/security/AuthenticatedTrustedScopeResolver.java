package com.hackalem.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/** Catalog projection of D1's verified session; no caller-supplied roles or secondary token. */
@Component
public class AuthenticatedTrustedScopeResolver implements TrustedScopeResolver {
    @Override public CatalogAdminScope resolve(HttpServletRequest request) {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof com.hackalem.domain.port.Contracts.TrustedScope scope)) {
            return CatalogAdminScope.anonymous();
        }
        boolean admin = authentication.getAuthorities().stream().anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
        return new CatalogAdminScope(scope.principalId().toString(), admin);
    }

    @Override public CatalogAdminScope requireAdmin(HttpServletRequest request) {
        var scope = resolve(request);
        if (!scope.admin()) throw new AdminAccessException("ADMIN_ACCESS_DENIED", "Требуются права администратора каталога", false);
        return scope;
    }
}
