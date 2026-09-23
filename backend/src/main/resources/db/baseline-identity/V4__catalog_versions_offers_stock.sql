-- CAT-01. Развитие каталога V2 до versioned схемы с офферами, складами и импортом.
--
-- V1/V2 не редактируются. Эта миграция обязана проходить в двух режимах:
--   1) чистая БД: V1 → V2 → V3 (products пустая);
--   2) upgrade: в products уже лежат строки из V2 (id = hashCode артикула).
-- Данные V2 не теряются: полный снимок строки уходит в catalog_legacy_products,
-- содержимое переезжает в product_versions под версию каталога 'legacy-v2'.
--
-- Миграция не вызывает OpenAI и не ходит в сеть: embedding заполняет import job.

-- ─── Версии каталога ─────────────────────────────────────────────────────────
-- Каждый импорт создаёт версию. Публикация — CAS-переключение ACTIVE, поэтому
-- упавший батч не трогает уже активные данные.
CREATE TABLE catalog_versions (
    id                   BIGSERIAL PRIMARY KEY,
    label                VARCHAR(128) NOT NULL,
    source_version       VARCHAR(64)  NOT NULL,
    status               VARCHAR(16)  NOT NULL,
    embedding_status     VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    embedding_model      VARCHAR(128),
    embedding_dimensions INTEGER,
    -- Пространство векторов: 'live:<model>:<dims>' либо 'fake:<generator>:<dims>'.
    -- Живые и синтетические embeddings не смешиваются: поиск сверяет это значение.
    vector_space         VARCHAR(128) NOT NULL,
    content_hash         CHAR(64),
    product_count        INTEGER      NOT NULL DEFAULT 0,
    embedded_count       INTEGER      NOT NULL DEFAULT 0,
    import_job_id        BIGINT,
    error_code           VARCHAR(64),
    error_message        TEXT,
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    ready_at             TIMESTAMPTZ,
    published_at         TIMESTAMPTZ,
    superseded_at        TIMESTAMPTZ,
    failed_at            TIMESTAMPTZ,
    CONSTRAINT catalog_versions_status_chk
        CHECK (status IN ('PENDING', 'READY', 'ACTIVE', 'SUPERSEDED', 'FAILED')),
    CONSTRAINT catalog_versions_embedding_status_chk
        CHECK (embedding_status IN ('PENDING', 'RUNNING', 'READY', 'FAILED', 'SKIPPED'))
);

-- Ровно одна активная версия — гарантия уровня БД, а не только кода.
CREATE UNIQUE INDEX catalog_versions_single_active_idx
    ON catalog_versions (status) WHERE status = 'ACTIVE';
CREATE INDEX catalog_versions_status_created_idx ON catalog_versions (status, created_at DESC);

-- ─── Задания импорта ─────────────────────────────────────────────────────────
CREATE TABLE catalog_import_jobs (
    id                 BIGSERIAL PRIMARY KEY,
    job_type           VARCHAR(32)  NOT NULL DEFAULT 'CATALOG_IMPORT',
    status             VARCHAR(16)  NOT NULL,
    idempotency_key    VARCHAR(128),
    content_hash       CHAR(64)     NOT NULL,
    source_version     VARCHAR(64)  NOT NULL,
    requested_by       VARCHAR(128) NOT NULL,
    vector_space       VARCHAR(128) NOT NULL,
    catalog_version_id BIGINT REFERENCES catalog_versions (id) ON DELETE SET NULL,
    total_products     INTEGER      NOT NULL DEFAULT 0,
    imported_products  INTEGER      NOT NULL DEFAULT 0,
    embedded_products  INTEGER      NOT NULL DEFAULT 0,
    errors             JSONB        NOT NULL DEFAULT '[]'::jsonb,
    warnings           JSONB        NOT NULL DEFAULT '[]'::jsonb,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    started_at         TIMESTAMPTZ,
    finished_at        TIMESTAMPTZ,
    CONSTRAINT catalog_import_jobs_status_chk
        CHECK (status IN ('PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED'))
);

-- Повторный импорт с тем же ключом возвращает то же задание, а не второй прогон.
CREATE UNIQUE INDEX catalog_import_jobs_idempotency_idx
    ON catalog_import_jobs (idempotency_key) WHERE idempotency_key IS NOT NULL;
CREATE INDEX catalog_import_jobs_status_idx ON catalog_import_jobs (status, created_at DESC);

ALTER TABLE catalog_versions
    ADD CONSTRAINT catalog_versions_import_job_fk
    FOREIGN KEY (import_job_id) REFERENCES catalog_import_jobs (id) ON DELETE SET NULL;

-- ─── Архив строк V2 ──────────────────────────────────────────────────────────
-- Полная копия каждой строки до реструктуризации, включая embedding как текст.
CREATE TABLE catalog_legacy_products (
    product_id  BIGINT PRIMARY KEY,
    payload     JSONB       NOT NULL,
    archived_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

INSERT INTO catalog_legacy_products (product_id, payload)
SELECT p.id, jsonb_build_object(
        'id', p.id,
        'article', p.article,
        'name', p.name,
        'brand', p.brand,
        'category', p.category,
        'price', p.price,
        'currency', p.currency,
        'stock', p.stock,
        'source_url', p.source_url,
        'specs', p.specs,
        'search_text', p.search_text,
        'embedding', p.embedding::text,
        'created_at', p.created_at,
        'updated_at', p.updated_at)
FROM products p;

-- ─── products → таблица стабильной идентичности ──────────────────────────────
-- Содержимое уезжает в product_versions; здесь остаётся только идентичность:
-- стабильный id (не hashCode) + артикул с сохранёнными нулями и его нормализация.
ALTER TABLE products ADD COLUMN supplier_id        VARCHAR(64);
ALTER TABLE products ADD COLUMN article_normalized VARCHAR(255);
ALTER TABLE products ADD COLUMN status             VARCHAR(16) NOT NULL DEFAULT 'ACTIVE';
ALTER TABLE products ADD COLUMN quarantine_reason  TEXT;
ALTER TABLE products ADD COLUMN duplicate_of       BIGINT;

UPDATE products SET article_normalized = upper(btrim(article));

-- Дубликаты нормализованного артикула из V2 выявляем ДО уникального индекса.
-- Ни одна строка не удаляется: неканонические уходят в QUARANTINED со ссылкой
-- на канонический товар, чтобы импорт и поиск работали, а данные остались.
WITH ranked AS (
    SELECT id,
           row_number() OVER (
               PARTITION BY article_normalized
               ORDER BY updated_at DESC, id ASC
           ) AS rn,
           first_value(id) OVER (
               PARTITION BY article_normalized
               ORDER BY updated_at DESC, id ASC
           ) AS canonical_id
    FROM products
)
UPDATE products p
SET status = 'QUARANTINED',
    quarantine_reason = 'LEGACY_DUPLICATE_ARTICLE',
    duplicate_of = r.canonical_id
FROM ranked r
WHERE p.id = r.id AND r.rn > 1;

ALTER TABLE products ALTER COLUMN article_normalized SET NOT NULL;
ALTER TABLE products
    ADD CONSTRAINT products_status_chk CHECK (status IN ('ACTIVE', 'QUARANTINED'));
ALTER TABLE products
    ADD CONSTRAINT products_duplicate_of_fk FOREIGN KEY (duplicate_of) REFERENCES products (id);

-- Уникальность артикула — среди действующих товаров.
CREATE UNIQUE INDEX products_article_normalized_active_idx
    ON products (article_normalized) WHERE status = 'ACTIVE';
CREATE UNIQUE INDEX products_supplier_id_active_idx
    ON products (supplier_id) WHERE supplier_id IS NOT NULL AND status = 'ACTIVE';
CREATE INDEX products_article_normalized_idx ON products (article_normalized);

-- Старые индексы содержимого переезжают вместе с колонками в product_versions.
DROP INDEX IF EXISTS products_category_idx;
DROP INDEX IF EXISTS products_brand_idx;
DROP INDEX IF EXISTS products_price_idx;
DROP INDEX IF EXISTS products_stock_idx;
DROP INDEX IF EXISTS products_specs_idx;
DROP INDEX IF EXISTS products_embedding_hnsw_idx;

-- Новые id выдаёт последовательность. hashCode артикула как PK запрещён:
-- 'Aa' и 'BB' дают одинаковый String.hashCode() и затирали бы друг друга.
-- Старт выше Integer.MAX_VALUE, чтобы не столкнуться с legacy hashCode-id.
CREATE SEQUENCE products_generated_id_seq AS BIGINT START WITH 4000000000 INCREMENT BY 1;

-- ─── Содержимое каталога по версиям ──────────────────────────────────────────
CREATE TABLE product_versions (
    id                 BIGSERIAL PRIMARY KEY,
    catalog_version_id BIGINT       NOT NULL REFERENCES catalog_versions (id) ON DELETE CASCADE,
    product_id         BIGINT       NOT NULL REFERENCES products (id),
    name               TEXT         NOT NULL,
    brand              VARCHAR(255),
    category           VARCHAR(255),
    unit               VARCHAR(16)  NOT NULL DEFAULT 'pcs',
    minimum_quantity   NUMERIC(14, 3) NOT NULL DEFAULT 1,
    step_quantity      NUMERIC(14, 3) NOT NULL DEFAULT 1,
    specs              JSONB        NOT NULL DEFAULT '{}'::jsonb,
    certificates       JSONB        NOT NULL DEFAULT '[]'::jsonb,
    source_url         TEXT,
    source_version     VARCHAR(64)  NOT NULL,
    synthetic          BOOLEAN      NOT NULL DEFAULT FALSE,
    search_text        TEXT         NOT NULL,
    embedding          vector(1536),
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT product_versions_unique UNIQUE (catalog_version_id, product_id),
    CONSTRAINT product_versions_minimum_chk CHECK (minimum_quantity > 0),
    CONSTRAINT product_versions_step_chk CHECK (step_quantity > 0)
);

CREATE INDEX product_versions_category_idx ON product_versions (catalog_version_id, category);
CREATE INDEX product_versions_brand_idx ON product_versions (catalog_version_id, brand);
CREATE INDEX product_versions_specs_idx ON product_versions USING gin (specs);
CREATE INDEX product_versions_embedding_hnsw_idx
    ON product_versions USING hnsw (embedding vector_cosine_ops)
    WITH (m = 32, ef_construction = 200);

-- ─── Цены ────────────────────────────────────────────────────────────────────
-- Цена может быть неизвестна (legacy-строка без price): тогда строки оффера нет,
-- а API отдаёт priceStatus=UNKNOWN. Ноль и «неизвестно» — не одно и то же.
CREATE TABLE product_offers (
    id                 BIGSERIAL PRIMARY KEY,
    catalog_version_id BIGINT         NOT NULL REFERENCES catalog_versions (id) ON DELETE CASCADE,
    product_id         BIGINT         NOT NULL REFERENCES products (id),
    price              NUMERIC(14, 2) NOT NULL,
    currency           VARCHAR(3)     NOT NULL DEFAULT 'KZT',
    source_version     VARCHAR(64)    NOT NULL,
    observed_at        TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT product_offers_unique UNIQUE (catalog_version_id, product_id),
    CONSTRAINT product_offers_price_chk CHECK (price >= 0)
);

CREATE INDEX product_offers_price_idx ON product_offers (catalog_version_id, price);

-- ─── Остатки по складам ──────────────────────────────────────────────────────
-- boolean stock больше не источник истины. Статус и количество согласованы
-- ограничением: UNKNOWN/ON_ORDER не имеют количества, OUT_OF_STOCK — точный ноль.
CREATE TABLE warehouse_stock (
    id                 BIGSERIAL PRIMARY KEY,
    catalog_version_id BIGINT       NOT NULL REFERENCES catalog_versions (id) ON DELETE CASCADE,
    product_id         BIGINT       NOT NULL REFERENCES products (id),
    warehouse_id       VARCHAR(64)  NOT NULL,
    available_quantity NUMERIC(14, 3),
    status             VARCHAR(16)  NOT NULL,
    eligible           BOOLEAN      NOT NULL DEFAULT TRUE,
    source_version     VARCHAR(64)  NOT NULL,
    provenance         VARCHAR(32)  NOT NULL DEFAULT 'import',
    observed_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT warehouse_stock_unique UNIQUE (catalog_version_id, product_id, warehouse_id),
    CONSTRAINT warehouse_stock_status_chk
        CHECK (status IN ('IN_STOCK', 'OUT_OF_STOCK', 'ON_ORDER', 'UNKNOWN')),
    CONSTRAINT warehouse_stock_quantity_chk CHECK (
        (status IN ('UNKNOWN', 'ON_ORDER') AND available_quantity IS NULL)
        OR (status = 'OUT_OF_STOCK' AND available_quantity = 0)
        OR (status = 'IN_STOCK' AND available_quantity > 0)
    )
);

CREATE INDEX warehouse_stock_lookup_idx
    ON warehouse_stock (catalog_version_id, product_id, eligible);

-- ─── Перенос содержимого V2 в версию 'legacy-v2' ─────────────────────────────
DO $$
DECLARE
    legacy_version_id BIGINT;
    legacy_count      BIGINT;
BEGIN
    SELECT count(*) INTO legacy_count FROM products;
    IF legacy_count = 0 THEN
        RETURN;
    END IF;

    -- Версия активна: после upgrade каталог продолжает отвечать теми же товарами.
    -- Векторы V2 могли быть построены другой моделью, поэтому пространство
    -- помечено как legacy:unknown — семантический поиск потребует переиндексации.
    INSERT INTO catalog_versions (label, source_version, status, embedding_status,
                                  embedding_model, embedding_dimensions, vector_space,
                                  product_count, embedded_count, created_at, ready_at, published_at)
    VALUES ('legacy-v2', 'legacy-v2', 'ACTIVE', 'FAILED',
            NULL, 1536, 'legacy:unknown',
            legacy_count,
            (SELECT count(*) FROM products WHERE embedding IS NOT NULL),
            now(), now(), now())
    RETURNING id INTO legacy_version_id;

    INSERT INTO product_versions (catalog_version_id, product_id, name, brand, category,
                                  unit, minimum_quantity, step_quantity, specs, certificates,
                                  source_url, source_version, synthetic, search_text, embedding,
                                  created_at)
    SELECT legacy_version_id, p.id, p.name, p.brand, p.category,
           'pcs', 1, 1, p.specs, '[]'::jsonb,
           p.source_url, 'legacy-v2', FALSE, p.search_text, p.embedding,
           p.created_at
    FROM products p;

    INSERT INTO product_offers (catalog_version_id, product_id, price, currency,
                                source_version, observed_at)
    SELECT legacy_version_id, p.id, p.price, COALESCE(p.currency, 'KZT'),
           'legacy-v2', p.updated_at
    FROM products p
    WHERE p.price IS NOT NULL;

    -- Ключевое правило: boolean stock не превращается в количество 0/1.
    -- Количественного источника у V2 нет → статус UNKNOWN и NULL количество.
    -- Исходное значение флага сохранено в catalog_legacy_products.
    INSERT INTO warehouse_stock (catalog_version_id, product_id, warehouse_id,
                                 available_quantity, status, eligible,
                                 source_version, provenance, observed_at)
    SELECT legacy_version_id, p.id, 'LEGACY',
           NULL, 'UNKNOWN', TRUE,
           'legacy-v2', 'legacy-v2-boolean', p.updated_at
    FROM products p;
END $$;

-- Содержимое перенесено — старые колонки уходят из таблицы идентичности.
ALTER TABLE products DROP COLUMN name;
ALTER TABLE products DROP COLUMN brand;
ALTER TABLE products DROP COLUMN category;
ALTER TABLE products DROP COLUMN price;
ALTER TABLE products DROP COLUMN currency;
ALTER TABLE products DROP COLUMN stock;
ALTER TABLE products DROP COLUMN source_url;
ALTER TABLE products DROP COLUMN specs;
ALTER TABLE products DROP COLUMN search_text;
ALTER TABLE products DROP COLUMN embedding;

-- id из V2 сохранены как есть; последовательность не должна их пересечь.
SELECT setval('products_generated_id_seq',
              GREATEST((SELECT COALESCE(max(id), 0) FROM products) + 1, 4000000000),
              FALSE);

COMMENT ON TABLE products IS 'Стабильная идентичность товара: id + артикул. Содержимое — в product_versions.';
COMMENT ON COLUMN products.supplier_id IS 'Внешний стабильный ID поставщика из импорта; id в БД не меняется.';
COMMENT ON TABLE catalog_versions IS 'Версии каталога; ACTIVE переключается CAS при публикации импорта.';
COMMENT ON TABLE warehouse_stock IS 'Точные остатки по складам. UNKNOWN и ON_ORDER не имеют количества.';
COMMENT ON TABLE catalog_legacy_products IS 'Снимок строк products из V2 до реструктуризации (аудит).';
