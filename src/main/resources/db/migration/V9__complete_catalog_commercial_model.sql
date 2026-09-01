-- ---------------------------------------------------------------------------
-- Product commercial metadata
-- ---------------------------------------------------------------------------

ALTER TABLE catalog.products
    ADD COLUMN brand TEXT NULL;

ALTER TABLE catalog.products
    ADD CONSTRAINT ck_catalog_products_brand
        CHECK (
            brand IS NULL
            OR (
                char_length(brand) BETWEEN 1 AND 120
                AND brand = btrim(
                    brand,
                    U&'\0009\000A\000B\000C\000D\001C\001D\001E\001F\0020\00A0\1680\2000\2001\2002\2003\2004\2005\2006\2007\2008\2009\200A\2028\2029\202F\205F\3000'
                )
                AND brand !~ '[\u0000-\u001F\u007F-\u009F]'
            )
        );

-- ---------------------------------------------------------------------------
-- ProductVariant commercial metadata and lifecycle
-- ---------------------------------------------------------------------------

ALTER TABLE catalog.product_variants
    ADD COLUMN display_name TEXT NULL,
    ADD COLUMN gtin TEXT NULL,
    ADD COLUMN mpn TEXT NULL,
    ADD COLUMN status TEXT NULL;

--
-- Historical Variants existed before an explicit lifecycle was introduced.
-- They already represented operational sellable identities, therefore V9
-- preserves that historical meaning by backfilling them as ACTIVE.
--
UPDATE catalog.product_variants
SET status = 'ACTIVE';

--
-- Newly inserted Variants begin as DRAFT. The default also preserves
-- compatibility with adapters written against the pre-V9 four-column shape
-- until those adapters are explicitly evolved later in OH-011.
--
ALTER TABLE catalog.product_variants
    ALTER COLUMN status SET DEFAULT 'DRAFT',
    ALTER COLUMN status SET NOT NULL;

ALTER TABLE catalog.product_variants
    ADD CONSTRAINT ck_catalog_product_variants_display_name
        CHECK (
            display_name IS NULL
            OR (
                char_length(display_name) BETWEEN 1 AND 160
                AND display_name = btrim(
                    display_name,
                    U&'\0009\000A\000B\000C\000D\001C\001D\001E\001F\0020\00A0\1680\2000\2001\2002\2003\2004\2005\2006\2007\2008\2009\200A\2028\2029\202F\205F\3000'
                )
                AND display_name !~ '[\u0000-\u001F\u007F-\u009F]'
            )
        ),

    ADD CONSTRAINT ck_catalog_product_variants_gtin
        CHECK (
            gtin IS NULL
            OR (
                char_length(gtin) IN (8, 12, 13, 14)
                AND gtin ~ '^[0-9]+$'
                AND (
                    (ascii(substr(lpad(gtin, 14, '0'), 1, 1)) - 48) * 3
                    + (ascii(substr(lpad(gtin, 14, '0'), 2, 1)) - 48)
                    + (ascii(substr(lpad(gtin, 14, '0'), 3, 1)) - 48) * 3
                    + (ascii(substr(lpad(gtin, 14, '0'), 4, 1)) - 48)
                    + (ascii(substr(lpad(gtin, 14, '0'), 5, 1)) - 48) * 3
                    + (ascii(substr(lpad(gtin, 14, '0'), 6, 1)) - 48)
                    + (ascii(substr(lpad(gtin, 14, '0'), 7, 1)) - 48) * 3
                    + (ascii(substr(lpad(gtin, 14, '0'), 8, 1)) - 48)
                    + (ascii(substr(lpad(gtin, 14, '0'), 9, 1)) - 48) * 3
                    + (ascii(substr(lpad(gtin, 14, '0'), 10, 1)) - 48)
                    + (ascii(substr(lpad(gtin, 14, '0'), 11, 1)) - 48) * 3
                    + (ascii(substr(lpad(gtin, 14, '0'), 12, 1)) - 48)
                    + (ascii(substr(lpad(gtin, 14, '0'), 13, 1)) - 48) * 3
                    + (ascii(substr(lpad(gtin, 14, '0'), 14, 1)) - 48)
                ) % 10 = 0
            )
        ),

    ADD CONSTRAINT ck_catalog_product_variants_mpn
        CHECK (
            mpn IS NULL
            OR (
                char_length(mpn) BETWEEN 1 AND 70
                AND mpn = btrim(
                    mpn,
                    U&'\0009\000A\000B\000C\000D\001C\001D\001E\001F\0020\00A0\1680\2000\2001\2002\2003\2004\2005\2006\2007\2008\2009\200A\2028\2029\202F\205F\3000'
                )
                AND mpn !~ '[\u0000-\u001F\u007F-\u009F]'
            )
        ),

    ADD CONSTRAINT ck_catalog_product_variants_status
        CHECK (
            status IN (
                'DRAFT',
                'ACTIVE',
                'INACTIVE',
                'ARCHIVED'
            )
        );

-- ---------------------------------------------------------------------------
-- ProductVariant attributes
-- ---------------------------------------------------------------------------

CREATE TABLE catalog.product_variant_attributes (
    tenant_id UUID NOT NULL,
    variant_id UUID NOT NULL,
    attribute_key TEXT COLLATE "C" NOT NULL,
    attribute_value TEXT NOT NULL,

    CONSTRAINT pk_catalog_product_variant_attributes
        PRIMARY KEY (
            tenant_id,
            variant_id,
            attribute_key
        ),

    CONSTRAINT fk_catalog_product_variant_attributes_variant
        FOREIGN KEY (
            tenant_id,
            variant_id
        )
        REFERENCES catalog.product_variants (
            tenant_id,
            id
        )
        ON DELETE CASCADE,

    CONSTRAINT ck_catalog_product_variant_attributes_key
        CHECK (
            char_length(attribute_key) BETWEEN 1 AND 64
            AND attribute_key ~ '^[A-Za-z][A-Za-z0-9._-]*$'
        ),

    CONSTRAINT ck_catalog_product_variant_attributes_value
        CHECK (
            char_length(attribute_value) BETWEEN 1 AND 256
            AND attribute_value = btrim(
                attribute_value,
                U&'\0009\000A\000B\000C\000D\001C\001D\001E\001F\0020\00A0\1680\2000\2001\2002\2003\2004\2005\2006\2007\2008\2009\200A\2028\2029\202F\205F\3000'
            )
            AND attribute_value !~ '[\u0000-\u001F\u007F-\u009F]'
        )
);

-- ---------------------------------------------------------------------------
-- Variant base prices
-- ---------------------------------------------------------------------------

CREATE TABLE catalog.variant_base_prices (
    tenant_id UUID NOT NULL,
    variant_id UUID NOT NULL,
    currency_code TEXT COLLATE "C" NOT NULL,
    minor_units BIGINT NOT NULL,

    CONSTRAINT pk_catalog_variant_base_prices
        PRIMARY KEY (
            tenant_id,
            variant_id,
            currency_code
        ),

    CONSTRAINT fk_catalog_variant_base_prices_variant
        FOREIGN KEY (
            tenant_id,
            variant_id
        )
        REFERENCES catalog.product_variants (
            tenant_id,
            id
        )
        ON DELETE CASCADE,

    CONSTRAINT ck_catalog_variant_base_prices_currency
        CHECK (
            currency_code ~ '^[A-Z]{3}$'
        ),

    CONSTRAINT ck_catalog_variant_base_prices_minor_units
        CHECK (
            minor_units >= 0
        )
);

-- ---------------------------------------------------------------------------
-- Catalog media metadata
-- ---------------------------------------------------------------------------

CREATE TABLE catalog.media (
    tenant_id UUID NOT NULL,
    id UUID NOT NULL,
    product_id UUID NULL,
    variant_id UUID NULL,
    media_type TEXT NOT NULL,
    reference TEXT NOT NULL,
    alt_text TEXT NULL,
    sort_order INTEGER NOT NULL,
    is_primary BOOLEAN NOT NULL,

    CONSTRAINT pk_catalog_media
        PRIMARY KEY (
            tenant_id,
            id
        ),

    CONSTRAINT fk_catalog_media_product
        FOREIGN KEY (
            tenant_id,
            product_id
        )
        REFERENCES catalog.products (
            tenant_id,
            id
        )
        ON DELETE CASCADE,

    CONSTRAINT fk_catalog_media_variant
        FOREIGN KEY (
            tenant_id,
            variant_id
        )
        REFERENCES catalog.product_variants (
            tenant_id,
            id
        )
        ON DELETE CASCADE,

    CONSTRAINT ck_catalog_media_owner
        CHECK (
            (product_id IS NOT NULL AND variant_id IS NULL)
            OR
            (product_id IS NULL AND variant_id IS NOT NULL)
        ),

    CONSTRAINT ck_catalog_media_type
        CHECK (
            media_type IN (
                'IMAGE',
                'VIDEO',
                'DOCUMENT',
                'OTHER'
            )
        ),

    CONSTRAINT ck_catalog_media_reference
        CHECK (
            char_length(reference) BETWEEN 1 AND 2048
            AND reference = btrim(
                reference,
                U&'\0009\000A\000B\000C\000D\001C\001D\001E\001F\0020\00A0\1680\2000\2001\2002\2003\2004\2005\2006\2007\2008\2009\200A\2028\2029\202F\205F\3000'
            )
            AND reference !~ '[\u0000-\u001F\u007F-\u009F]'
        ),

    CONSTRAINT ck_catalog_media_alt_text
        CHECK (
            alt_text IS NULL
            OR (
                char_length(alt_text) BETWEEN 1 AND 512
                AND alt_text = btrim(
                    alt_text,
                    U&'\0009\000A\000B\000C\000D\001C\001D\001E\001F\0020\00A0\1680\2000\2001\2002\2003\2004\2005\2006\2007\2008\2009\200A\2028\2029\202F\205F\3000'
                )
                AND alt_text !~ '[\u0000-\u001F\u007F-\u009F]'
            )
        ),

    CONSTRAINT ck_catalog_media_sort_order
        CHECK (
            sort_order >= 0
        )
);

CREATE UNIQUE INDEX uq_catalog_media_primary_product
    ON catalog.media (
        tenant_id,
        product_id
    )
    WHERE is_primary
      AND product_id IS NOT NULL;

CREATE UNIQUE INDEX uq_catalog_media_primary_variant
    ON catalog.media (
        tenant_id,
        variant_id
    )
    WHERE is_primary
      AND variant_id IS NOT NULL;

CREATE INDEX ix_catalog_media_product
    ON catalog.media (
        tenant_id,
        product_id,
        sort_order,
        id
    )
    WHERE product_id IS NOT NULL;

CREATE INDEX ix_catalog_media_variant
    ON catalog.media (
        tenant_id,
        variant_id,
        sort_order,
        id
    )
    WHERE variant_id IS NOT NULL;