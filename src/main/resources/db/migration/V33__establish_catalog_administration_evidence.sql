-- Resource revisions protect stale administrative snapshots; domain identities stay unchanged.
ALTER TABLE catalog.products
    ADD COLUMN revision BIGINT NOT NULL DEFAULT 1,
    ADD CONSTRAINT ck_catalog_product_revision CHECK (revision > 0);
ALTER TABLE catalog.product_variants
    ADD COLUMN revision BIGINT NOT NULL DEFAULT 1,
    ADD CONSTRAINT ck_catalog_variant_revision CHECK (revision > 0);

CREATE TABLE catalog.administrative_audit_events (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    actor_id UUID NOT NULL,
    resource_id UUID NOT NULL,
    action TEXT NOT NULL,
    before_revision BIGINT NOT NULL,
    after_revision BIGINT NOT NULL,
    correlation_id VARCHAR(128) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_catalog_admin_action CHECK (action IN (
        'PRODUCT_CREATED', 'PRODUCT_METADATA_CHANGED', 'PRODUCT_ACTIVATED', 'PRODUCT_ARCHIVED',
        'VARIANT_CREATED', 'VARIANT_METADATA_CHANGED', 'VARIANT_ACTIVATED', 'VARIANT_DEACTIVATED', 'VARIANT_ARCHIVED'
    )),
    CONSTRAINT ck_catalog_admin_revision CHECK (
        before_revision >= 0 AND before_revision < 9223372036854775807
        AND after_revision = before_revision + 1
        AND ((action IN ('PRODUCT_CREATED', 'VARIANT_CREATED') AND before_revision = 0)
            OR (action NOT IN ('PRODUCT_CREATED', 'VARIANT_CREATED') AND before_revision > 0))
    ),
    CONSTRAINT ck_catalog_admin_correlation CHECK (correlation_id ~ '^[A-Za-z0-9._:-]{1,128}$')
);
-- Historical evidence deliberately carries no live aggregate or cross-module foreign keys.
CREATE FUNCTION catalog.reject_administrative_audit_mutation()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE check_violation USING MESSAGE = 'Catalog evidence is append-only',
        CONSTRAINT = 'ck_catalog_admin_append_only';
    RETURN NULL;
END;
$$;
CREATE TRIGGER trg_catalog_admin_append_only
    BEFORE UPDATE OR DELETE ON catalog.administrative_audit_events
    FOR EACH ROW EXECUTE FUNCTION catalog.reject_administrative_audit_mutation();
