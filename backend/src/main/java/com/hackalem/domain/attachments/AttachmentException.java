package com.hackalem.domain.attachments;
import org.springframework.http.HttpStatus;
public class AttachmentException extends com.hackalem.web.ApiException {
    private final HttpStatus status;
    private final String code;
    public AttachmentException(HttpStatus status, String code) { super(status.value(),code); this.status=status; this.code=code; }
    public HttpStatus status() { return status; }
    public String code() { return code; }
    public static AttachmentException invalid(String code) { return new AttachmentException(HttpStatus.UNPROCESSABLE_ENTITY,code); }
}
