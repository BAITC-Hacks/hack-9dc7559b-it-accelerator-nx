-- Append to the next common migration. Existing D1 DEMO offers are unchanged.
ALTER TABLE sample_offers ADD COLUMN catalog_version_id BIGINT;
ALTER TABLE sample_offers ADD COLUMN source_version VARCHAR(100);
ALTER TABLE sample_offers ADD COLUMN minimum_quantity NUMERIC(24,6) NOT NULL DEFAULT 1;
ALTER TABLE sample_offers ADD COLUMN observed_at TIMESTAMPTZ NOT NULL DEFAULT now();
CREATE TABLE catalog_offer_seed (
    product_id BIGINT PRIMARY KEY REFERENCES products(id),
    catalog_version_id BIGINT NOT NULL,
    source_version VARCHAR(100) NOT NULL
);
CREATE TABLE catalog_read_snapshots (
    id UUID PRIMARY KEY,
    owner_id UUID NOT NULL REFERENCES visitor_sessions(id) ON DELETE CASCADE,
    kind VARCHAR(24) NOT NULL,
    payload JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL DEFAULT now() + interval '24 hours'
);
CREATE INDEX catalog_read_snapshots_expiry ON catalog_read_snapshots(expires_at);


-- Immutable KB versions, separate from products and private attachments.
CREATE TABLE documents (
    id UUID PRIMARY KEY,
    external_id VARCHAR(120) NOT NULL UNIQUE,
    title VARCHAR(300) NOT NULL,
    visibility VARCHAR(16) NOT NULL CHECK (visibility IN ('PUBLIC', 'PRIVATE')),
    owner_id UUID NOT NULL,
    active_version_id UUID,
    desired_version_id UUID,
    tombstoned BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE document_versions (
    id UUID PRIMARY KEY,
    document_id UUID NOT NULL REFERENCES documents(id),
    version_label VARCHAR(100) NOT NULL,
    title VARCHAR(300) NOT NULL,
    source_object_key VARCHAR(300) NOT NULL UNIQUE,
    source_sha256 CHAR(64) NOT NULL,
    source_url VARCHAR(2000),
    original_text TEXT NOT NULL,
    tags TEXT NOT NULL,
    synthetic BOOLEAN NOT NULL,
    embedding_model VARCHAR(200) NOT NULL,
    embedding_dimensions INT NOT NULL,
    state VARCHAR(16) NOT NULL CHECK (state IN ('PENDING', 'READY', 'FAILED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE ingestion_jobs (
    id UUID PRIMARY KEY,
    document_id UUID NOT NULL REFERENCES documents(id),
    version_id UUID NOT NULL REFERENCES document_versions(id),
    state VARCHAR(20) NOT NULL CHECK (state IN ('QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'SUPERSEDED')),
    lease_epoch BIGINT NOT NULL DEFAULT 0,
    attempts INT NOT NULL DEFAULT 0,
    lease_until TIMESTAMPTZ,
    error_code VARCHAR(100),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ingestion_jobs_claim_idx ON ingestion_jobs(state, lease_until, created_at);
CREATE TABLE document_chunks (
    id UUID PRIMARY KEY,
    document_id UUID NOT NULL REFERENCES documents(id),
    version_id UUID NOT NULL REFERENCES document_versions(id),
    ordinal INT NOT NULL,
    page_number INT NOT NULL,
    heading VARCHAR(500) NOT NULL,
    content TEXT NOT NULL,
    suspicious BOOLEAN NOT NULL,
    embedding vector(1536),
    UNIQUE(version_id, ordinal)
);
CREATE INDEX document_chunks_scope_idx ON document_chunks(document_id, version_id);
CREATE TABLE message_citations (
    id UUID PRIMARY KEY,
    principal_id UUID NOT NULL,
    chunk_id UUID NOT NULL REFERENCES document_chunks(id),
    version_id UUID NOT NULL REFERENCES document_versions(id),
    retrieval_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(retrieval_id, chunk_id)
);
CREATE INDEX message_citations_principal_idx ON message_citations(principal_id, retrieval_id);


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

-- QA-02 per-round provider evidence. No prompts or document bodies are stored here.
CREATE TABLE agent_model_usage (
 run_id UUID NOT NULL REFERENCES chat_runs(id) ON DELETE CASCADE,
 epoch BIGINT NOT NULL, round INTEGER NOT NULL, model VARCHAR(200),
 input_tokens INTEGER NOT NULL, output_tokens INTEGER NOT NULL, measured BOOLEAN NOT NULL,
 PRIMARY KEY(run_id,epoch,round)
);
