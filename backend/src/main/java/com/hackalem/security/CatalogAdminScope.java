package com.hackalem.security;

/**
 * Принципал запроса в объёме, который нужен каталогу.
 *
 * Проекция проверенной AUTH-01 сессии и серверной роли ADMIN.
 */
public record CatalogAdminScope(String principal, boolean admin) {

    public static CatalogAdminScope anonymous() {
        return new CatalogAdminScope("anonymous", false);
    }
}
