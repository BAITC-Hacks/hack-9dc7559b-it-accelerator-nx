-- D1 baseline; V1/V2 remain byte-for-byte unchanged. D2 receives next V4/V5.
CREATE TABLE visitor_sessions (
    id uuid PRIMARY KEY, cart_id uuid NOT NULL UNIQUE, expires_at timestamptz NOT NULL,
    revoked boolean NOT NULL DEFAULT false, created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE conversations (
    id uuid PRIMARY KEY, owner_id uuid NOT NULL REFERENCES visitor_sessions(id),
    version bigint NOT NULL DEFAULT 0, next_seq bigint NOT NULL DEFAULT 0,
    state jsonb NOT NULL, created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX conversations_owner_page ON conversations(owner_id,created_at,id);
CREATE TABLE chat_runs (
    id uuid PRIMARY KEY, conversation_id uuid NOT NULL REFERENCES conversations(id),
    owner_id uuid NOT NULL REFERENCES visitor_sessions(id), message_id uuid NOT NULL,
    idempotency_key varchar(128) NOT NULL, payload_hash char(64) NOT NULL,
    state varchar(24) NOT NULL CHECK(state IN ('queued','generating','completed','failed','cancelled')),
    epoch bigint NOT NULL DEFAULT 0, event_seq bigint NOT NULL DEFAULT 0,
    lease_until timestamptz, deadline timestamptz NOT NULL, text text NOT NULL DEFAULT '',
    error_code varchar(80), created_at timestamptz NOT NULL DEFAULT now(), finished_at timestamptz,
    UNIQUE(conversation_id,idempotency_key)
);
CREATE UNIQUE INDEX one_active_run ON chat_runs(conversation_id) WHERE state IN ('queued','generating');
CREATE INDEX queued_runs ON chat_runs(state,created_at);
CREATE TABLE messages (
    id uuid PRIMARY KEY, conversation_id uuid NOT NULL REFERENCES conversations(id),
    seq bigint NOT NULL, role varchar(12) NOT NULL, text text NOT NULL,
    run_id uuid REFERENCES chat_runs(id), created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE(conversation_id,seq), UNIQUE(run_id,role)
);
CREATE TABLE run_events (
    run_id uuid NOT NULL REFERENCES chat_runs(id), seq bigint NOT NULL, epoch bigint NOT NULL,
    body jsonb NOT NULL, created_at timestamptz NOT NULL DEFAULT now(), PRIMARY KEY(run_id,seq)
);
CREATE TABLE result_sets (
    id uuid PRIMARY KEY, conversation_id uuid NOT NULL REFERENCES conversations(id),
    owner_id uuid NOT NULL REFERENCES visitor_sessions(id), body jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE tool_invocations (
    run_id uuid NOT NULL REFERENCES chat_runs(id), call_id varchar(160) NOT NULL,
    name varchar(80) NOT NULL, args_hash char(64) NOT NULL, result jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(), PRIMARY KEY(run_id,call_id)
);
CREATE TABLE cart_proposals (
    id uuid PRIMARY KEY, conversation_id uuid NOT NULL REFERENCES conversations(id),
    owner_id uuid NOT NULL REFERENCES visitor_sessions(id), cart_id uuid NOT NULL,
    revision bigint NOT NULL, digest char(64) NOT NULL, operation_id uuid NOT NULL UNIQUE,
    expected_cart_version bigint NOT NULL, state varchar(24) NOT NULL DEFAULT 'pending',
    expires_at timestamptz NOT NULL, lines jsonb NOT NULL, dedup_key varchar(200) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(), UNIQUE(conversation_id,dedup_key)
);
CREATE UNIQUE INDEX one_pending_proposal ON cart_proposals(conversation_id) WHERE state='pending';
CREATE TABLE cart_operations (
    id uuid PRIMARY KEY, proposal_id uuid NOT NULL UNIQUE REFERENCES cart_proposals(id),
    owner_id uuid NOT NULL REFERENCES visitor_sessions(id), state varchar(24) NOT NULL,
    consent_origin varchar(20) NOT NULL, request_hash char(64) NOT NULL,
    result jsonb, created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE confirmation_keys (
    owner_id uuid NOT NULL REFERENCES visitor_sessions(id), idempotency_key varchar(128) NOT NULL,
    request_hash char(64) NOT NULL, operation_id uuid NOT NULL REFERENCES cart_operations(id),
    PRIMARY KEY(owner_id,idempotency_key)
);
CREATE TABLE carts (
    id uuid PRIMARY KEY, owner_id uuid NOT NULL UNIQUE REFERENCES visitor_sessions(id), version bigint NOT NULL DEFAULT 0
);
CREATE TABLE cart_lines (
    cart_id uuid NOT NULL REFERENCES carts(id), article varchar(200) NOT NULL, unit varchar(40) NOT NULL,
    warehouse varchar(100) NOT NULL, bucket varchar(250) NOT NULL,
    quantity numeric(24,6) NOT NULL CHECK(quantity>0), price numeric(24,6) NOT NULL,
    currency varchar(8) NOT NULL, step numeric(24,6) NOT NULL,
    PRIMARY KEY(cart_id,bucket)
);
CREATE TABLE sample_cart_outcomes (
    operation_id uuid PRIMARY KEY, owner_id uuid NOT NULL, body jsonb NOT NULL
);
-- Isolated test authoritative store; no production catalog or availability inference.
CREATE TABLE sample_offers (
    article varchar(200) NOT NULL, unit varchar(40) NOT NULL, warehouse varchar(100) NOT NULL,
    bucket varchar(250) NOT NULL UNIQUE, price numeric(24,6) NOT NULL, currency varchar(8) NOT NULL,
    available numeric(24,6) NOT NULL, step numeric(24,6) NOT NULL, version bigint NOT NULL DEFAULT 1,
    PRIMARY KEY(article,unit,warehouse)
);
