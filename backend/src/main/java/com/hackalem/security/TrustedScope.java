package com.hackalem.security;

/**
 * Принципал запроса в объёме, который нужен каталогу.
 *
 * Временная форма до AUTH-01: там появится настоящая visitor/partner identity,
 * и этот тип станет её проекцией. Ни один вызов не берёт принципала из тела
 * запроса — только из заголовков, проверенных сервером.
 */
public record TrustedScope(String principal, boolean admin) {

    public static TrustedScope anonymous() {
        return new TrustedScope("anonymous", false);
    }
}
