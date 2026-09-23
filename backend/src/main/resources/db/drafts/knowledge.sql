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
