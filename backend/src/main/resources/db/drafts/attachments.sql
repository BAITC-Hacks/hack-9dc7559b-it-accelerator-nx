-- D2 draft: merge into reserved V5 with knowledge schema. Original bytes are private,
-- transactionally stored with metadata and job; no filesystem orphan/object ACL gap.
CREATE TABLE attachment_queue_guard (id INTEGER PRIMARY KEY CHECK (id = 1));
INSERT INTO attachment_queue_guard(id) VALUES (1);
CREATE TABLE attachments (
 id VARCHAR(36) PRIMARY KEY, owner_subject UUID NOT NULL REFERENCES visitor_sessions(id) ON DELETE CASCADE, conversation_id UUID NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
 revision BIGINT NOT NULL DEFAULT 1, desired_version INTEGER NOT NULL DEFAULT 1,
 status VARCHAR(32) NOT NULL, stage VARCHAR(32) NOT NULL,
 filename VARCHAR(255) NOT NULL, mime_type VARCHAR(150) NOT NULL, extension VARCHAR(8) NOT NULL,
 content_hash VARCHAR(64) NOT NULL, original_bytes BYTEA,
 warnings TEXT NOT NULL DEFAULT '[]', error_code VARCHAR(100), deleted BOOLEAN NOT NULL DEFAULT FALSE,
 created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX attachments_scope_hash ON attachments(owner_subject, conversation_id, content_hash);
CREATE TABLE attachment_jobs (
 id VARCHAR(36) PRIMARY KEY, attachment_id VARCHAR(36) NOT NULL REFERENCES attachments(id) ON DELETE CASCADE,
 desired_version INTEGER NOT NULL, status VARCHAR(24) NOT NULL DEFAULT 'QUEUED',
 epoch BIGINT NOT NULL DEFAULT 0, attempts INTEGER NOT NULL DEFAULT 0,
 lease_until TIMESTAMP WITH TIME ZONE, created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX attachment_jobs_claim ON attachment_jobs(status, lease_until);
CREATE TABLE attachment_rows (
 attachment_id VARCHAR(36) NOT NULL REFERENCES attachments(id) ON DELETE CASCADE, row_id VARCHAR(40) NOT NULL,
 extraction_version INTEGER NOT NULL, row_order INTEGER NOT NULL, payload TEXT NOT NULL,
 PRIMARY KEY(attachment_id, row_id)
);
CREATE TABLE attachment_reviews (
 attachment_id VARCHAR(36) NOT NULL REFERENCES attachments(id) ON DELETE CASCADE, row_id VARCHAR(40) NOT NULL,
 payload TEXT NOT NULL, reviewed_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY(attachment_id, row_id)
);
