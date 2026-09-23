package com.hackalem.ai.attachments;
public interface OcrGateway {
    boolean available();
    String recognize(byte[] image, long deadlineEpochMillis);
}
