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
