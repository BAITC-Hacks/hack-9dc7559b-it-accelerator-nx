package com.hackalem.web;
public class ApiException extends RuntimeException {
    public final int status;
    public final String code;
    public ApiException(int status, String code) { super(code); this.status=status; this.code=code; }
    public static ApiException missing() { return new ApiException(404,"resource_not_found"); }
    public static ApiException conflict(String code) { return new ApiException(409,code); }
    public static ApiException unavailable(String code) { return new ApiException(503,code); }
}
