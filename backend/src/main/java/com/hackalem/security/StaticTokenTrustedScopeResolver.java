package com.hackalem.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Временная реализация админского доступа: общий токен из env ADMIN_API_TOKEN
 * в заголовке X-Admin-Token.
 *
 * Это заглушка до AUTH-01, а не целевая модель: настоящая проверка admin-роли
 * появится вместе с identity и будет привязана к серверной сессии, а не к
 * общему секрету. Пустой токен не открывает доступ, а выключает endpoint:
 * незаданная переменная не должна означать «можно всем».
 */
@Component
public class StaticTokenTrustedScopeResolver implements TrustedScopeResolver {

    public static final String ADMIN_TOKEN_HEADER = "X-Admin-Token";

    private final String adminToken;

    public StaticTokenTrustedScopeResolver(@Value("${app.admin.token:}") String adminToken) {
        this.adminToken = adminToken == null ? "" : adminToken.strip();
    }

    @Override
    public TrustedScope resolve(HttpServletRequest request) {
        return isAdmin(request) ? new TrustedScope("admin", true) : TrustedScope.anonymous();
    }

    @Override
    public TrustedScope requireAdmin(HttpServletRequest request) {
        if (adminToken.isEmpty()) {
            throw new AdminAccessException("ADMIN_ACCESS_NOT_CONFIGURED",
                    "Админский доступ не настроен: задайте ADMIN_API_TOKEN", true);
        }
        if (!isAdmin(request)) {
            throw new AdminAccessException("ADMIN_ACCESS_DENIED",
                    "Требуются права администратора каталога", false);
        }
        return new TrustedScope("admin", true);
    }

    private boolean isAdmin(HttpServletRequest request) {
        if (adminToken.isEmpty()) {
            return false;
        }
        String provided = request.getHeader(ADMIN_TOKEN_HEADER);
        if (provided == null) {
            return false;
        }
        return MessageDigest.isEqual(provided.strip().getBytes(StandardCharsets.UTF_8),
                adminToken.getBytes(StandardCharsets.UTF_8));
    }
}
