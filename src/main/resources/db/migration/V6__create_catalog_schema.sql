CREATE SCHEMA catalog;

-- ---------------------------------------------------------------------------
-- Products
-- ---------------------------------------------------------------------------

CREATE TABLE catalog.products (
    tenant_id UUID NOT NULL,
    id UUID NOT NULL,
    name TEXT NOT NULL,
    slug VARCHAR(256) COLLATE "C" NOT NULL,
    description TEXT NULL,
    status TEXT NOT NULL,

    CONSTRAINT pk_catalog_products
        PRIMARY KEY (tenant_id, id),

    CONSTRAINT uq_catalog_products_tenant_slug
        UNIQUE (tenant_id, slug),

    CONSTRAINT ck_catalog_products_name
        CHECK (
            char_length(name) > 0
            AND name = btrim(
                name,
                U&'\0009\000A\000B\000C\000D\001C\001D\001E\001F\0020\00A0\1680\2000\2001\2002\2003\2004\2005\2006\2007\2008\2009\200A\2028\2029\202F\205F\3000'
            )
        ),

    CONSTRAINT ck_catalog_products_slug
        CHECK (
            char_length(slug) BETWEEN 2 AND 256
            AND slug ~ '^[A-Za-z0-9_-]+$'
        ),

    CONSTRAINT ck_catalog_products_status
        CHECK (
            status IN (
                'DRAFT',
                'ACTIVE',
                'ARCHIVED'
            )
        )
);

-- ---------------------------------------------------------------------------
-- Product variants
-- ---------------------------------------------------------------------------

CREATE TABLE catalog.product_variants (
    tenant_id UUID NOT NULL,
    id UUID NOT NULL,
    product_id UUID NOT NULL,
    sku VARCHAR(64) COLLATE "C" NOT NULL,

    CONSTRAINT pk_catalog_product_variants
        PRIMARY KEY (tenant_id, id),

    CONSTRAINT uq_catalog_product_variants_tenant_sku
        UNIQUE (tenant_id, sku),

    CONSTRAINT fk_catalog_product_variants_product
        FOREIGN KEY (tenant_id, product_id)
        REFERENCES catalog.products (tenant_id, id),

    CONSTRAINT ck_catalog_product_variants_sku_length
        CHECK (
            char_length(sku) BETWEEN 1 AND 64
        ),

    CONSTRAINT ck_catalog_product_variants_sku_whitespace
        CHECK (
            sku = btrim(
                sku,
                U&'\0009\000A\000B\000C\000D\001C\001D\001E\001F\0020\00A0\1680\2000\2001\2002\2003\2004\2005\2006\2007\2008\2009\200A\2028\2029\202F\205F\3000'
            )
        ),

    CONSTRAINT ck_catalog_product_variants_sku_control_characters
        CHECK (
            sku !~ '[\u0000-\u001F\u007F-\u009F]'
        )
);

CREATE INDEX ix_catalog_product_variants_product
    ON catalog.product_variants (
        tenant_id,
        product_id
    );

-- ---------------------------------------------------------------------------
-- Categories
-- ---------------------------------------------------------------------------

CREATE TABLE catalog.categories (
    tenant_id UUID NOT NULL,
    id UUID NOT NULL,
    parent_category_id UUID NULL,
    name TEXT NOT NULL,
    slug VARCHAR(256) COLLATE "C" NOT NULL,
    description TEXT NULL,

    CONSTRAINT pk_catalog_categories
        PRIMARY KEY (tenant_id, id),

    CONSTRAINT uq_catalog_categories_tenant_slug
        UNIQUE (tenant_id, slug),

    CONSTRAINT fk_catalog_categories_parent
        FOREIGN KEY (
            tenant_id,
            parent_category_id
        )
        REFERENCES catalog.categories (
            tenant_id,
            id
        ),

    CONSTRAINT ck_catalog_categories_name
        CHECK (
            char_length(name) > 0
            AND name = btrim(
                name,
                U&'\0009\000A\000B\000C\000D\001C\001D\001E\001F\0020\00A0\1680\2000\2001\2002\2003\2004\2005\2006\2007\2008\2009\200A\2028\2029\202F\205F\3000'
            )
        ),

    CONSTRAINT ck_catalog_categories_slug
        CHECK (
            char_length(slug) BETWEEN 2 AND 256
            AND slug ~ '^[A-Za-z0-9_-]+$'
        ),

    CONSTRAINT ck_catalog_categories_not_self_parent
        CHECK (
            parent_category_id IS NULL
            OR parent_category_id <> id
        )
);

CREATE INDEX ix_catalog_categories_parent
    ON catalog.categories (
        tenant_id,
        parent_category_id
    )
    WHERE parent_category_id IS NOT NULL;

-- ---------------------------------------------------------------------------
-- Product ↔ Category assignments
-- ---------------------------------------------------------------------------

CREATE TABLE catalog.product_categories (
    tenant_id UUID NOT NULL,
    product_id UUID NOT NULL,
    category_id UUID NOT NULL,

    CONSTRAINT pk_catalog_product_categories
        PRIMARY KEY (
            tenant_id,
            product_id,
            category_id
        ),

    CONSTRAINT fk_catalog_product_categories_product
        FOREIGN KEY (
            tenant_id,
            product_id
        )
        REFERENCES catalog.products (
            tenant_id,
            id
        )
        ON DELETE CASCADE,

    CONSTRAINT fk_catalog_product_categories_category
        FOREIGN KEY (
            tenant_id,
            category_id
        )
        REFERENCES catalog.categories (
            tenant_id,
            id
        )
        ON DELETE CASCADE
);

CREATE INDEX ix_catalog_product_categories_category
    ON catalog.product_categories (
        tenant_id,
        category_id,
        product_id
    );