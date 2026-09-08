ALTER TABLE catalog.categories ADD COLUMN revision BIGINT NOT NULL DEFAULT 1,
    ADD CONSTRAINT ck_catalog_category_revision CHECK (revision > 0);
ALTER TABLE catalog.variant_base_prices ADD COLUMN revision BIGINT NOT NULL DEFAULT 1,
    ADD CONSTRAINT ck_catalog_price_revision CHECK (revision > 0);

-- V33's row trigger cannot intercept TRUNCATE; preserve all historical evidence.
CREATE TRIGGER trg_catalog_admin_no_truncate
    BEFORE TRUNCATE ON catalog.administrative_audit_events
    FOR EACH STATEMENT EXECUTE FUNCTION catalog.reject_administrative_audit_mutation();

ALTER TABLE catalog.administrative_audit_events
    ADD COLUMN currency_code TEXT,
    ADD COLUMN before_minor_units BIGINT,
    ADD COLUMN after_minor_units BIGINT,
    DROP CONSTRAINT ck_catalog_admin_action,
    DROP CONSTRAINT ck_catalog_admin_revision;
ALTER TABLE catalog.administrative_audit_events
    ADD CONSTRAINT ck_catalog_admin_action CHECK (action IN (
        'PRODUCT_CREATED','PRODUCT_METADATA_CHANGED','PRODUCT_ACTIVATED','PRODUCT_ARCHIVED','PRODUCT_CATEGORIES_CHANGED',
        'VARIANT_CREATED','VARIANT_METADATA_CHANGED','VARIANT_ACTIVATED','VARIANT_DEACTIVATED','VARIANT_ARCHIVED',
        'CATEGORY_CREATED','CATEGORY_METADATA_CHANGED','CATEGORY_REPARENTED','BASE_PRICE_SET')),
    ADD CONSTRAINT ck_catalog_admin_revision CHECK (
        before_revision >= 0 AND before_revision < 9223372036854775807 AND after_revision = before_revision + 1
        AND ((action IN ('PRODUCT_CREATED','VARIANT_CREATED','CATEGORY_CREATED') AND before_revision=0)
            OR action='BASE_PRICE_SET'
            OR (action NOT IN ('PRODUCT_CREATED','VARIANT_CREATED','CATEGORY_CREATED') AND before_revision>0))),
    ADD CONSTRAINT ck_catalog_admin_price_evidence CHECK (
        (action='BASE_PRICE_SET' AND currency_code IS NOT NULL AND currency_code ~ '^[A-Z]{3}$'
            AND after_minor_units IS NOT NULL AND after_minor_units>=0
            AND ((before_revision=0 AND before_minor_units IS NULL)
                OR (before_revision>0 AND before_minor_units IS NOT NULL AND before_minor_units>=0)))
        OR (action<>'BASE_PRICE_SET' AND currency_code IS NULL AND before_minor_units IS NULL AND after_minor_units IS NULL));
