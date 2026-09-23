package com.hackalem.domain.attachments;
public final class AttachmentLimits {
    private AttachmentLimits() {}
    public static final int BYTES=10*1024*1024, ROWS=1000, PAGES=20, TEXT=200_000, CELL=2000;
    public static final long PIXELS=20_000_000, DEADLINE_MILLIS=30_000, LEASE_SECONDS=60;
    public static final int QUEUE=100, OWNER_QUEUE=20, ATTEMPTS=3;
}
