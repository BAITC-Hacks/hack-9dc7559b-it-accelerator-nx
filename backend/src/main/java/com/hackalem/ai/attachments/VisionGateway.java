package com.hackalem.ai.attachments;
import java.util.List;
/** Optional provider adapter; no network call or invented recognition in default runtime. */
public interface VisionGateway {
    record Observation(String rawText, String category, List<String> visibleMarkings,
                       java.util.Map<String,String> observedAttributes, List<String> qualityFlags) {}
    boolean available();
    Observation inspect(byte[] jpeg, long deadlineEpochMillis);
}
