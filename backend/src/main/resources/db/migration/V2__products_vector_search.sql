CREATE TABLE products (
    id BIGINT PRIMARY KEY,
    article VARCHAR(255) NOT NULL,
    name TEXT NOT NULL,
    brand VARCHAR(255),
    category VARCHAR(255),
    price NUMERIC(14, 2),
    currency VARCHAR(3) NOT NULL DEFAULT 'KZT',
    stock BOOLEAN NOT NULL DEFAULT TRUE,
    source_url TEXT,
    specs JSONB NOT NULL DEFAULT '{}'::jsonb,
    search_text TEXT NOT NULL,
    embedding vector(1536),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX products_category_idx ON products (category);
CREATE INDEX products_brand_idx ON products (brand);
CREATE INDEX products_price_idx ON products (price);
CREATE INDEX products_stock_idx ON products (stock);
CREATE INDEX products_specs_idx ON products USING gin (specs);
CREATE INDEX products_embedding_hnsw_idx
    ON products USING hnsw (embedding vector_cosine_ops)
    WITH (m = 32, ef_construction = 200);
