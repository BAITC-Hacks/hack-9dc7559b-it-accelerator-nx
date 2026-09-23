package com.hackalem.security;

import jakarta.servlet.http.HttpServletRequest;

/** Определяет принципала запроса и проверяет админские права. */
public interface TrustedScopeResolver {

    CatalogAdminScope resolve(HttpServletRequest request);

    /** Бросает {@link AdminAccessException}, если у запроса нет админских прав. */
    CatalogAdminScope requireAdmin(HttpServletRequest request);
}
