package com.hackalem.security;

/** Доступ к админскому endpoint запрещён или не настроен. */
public class AdminAccessException extends RuntimeException {

    private final String code;
    private final boolean configurationIssue;

    public AdminAccessException(String code, String message, boolean configurationIssue) {
        super(message);
        this.code = code;
        this.configurationIssue = configurationIssue;
    }

    public String code() {
        return code;
    }

    /** true — сервер не настроен (503), false — запрос не имеет прав (403). */
    public boolean configurationIssue() {
        return configurationIssue;
    }
}
