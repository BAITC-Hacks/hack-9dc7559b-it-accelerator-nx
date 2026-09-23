package com.hackalem.domain.port;
import static com.hackalem.domain.port.Contracts.*;
public interface AttachmentPort { ReviewedItems getReviewedItems(String attachmentId, String version, TrustedScope scope); }
