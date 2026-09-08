-- Operational movements are both the physical-stock evidence and retry outcome.
-- All writes participate in the Inventory administration transaction. External
-- Tenant, User and Variant identities deliberately have no cross-module FK.
CREATE TABLE inventory.movements (
    tenant_id UUID NOT NULL,
    operation_id UUID NOT NULL,
    actor_user_id UUID NOT NULL,
    variant_id UUID NOT NULL,
    movement_type TEXT NOT NULL,
    delta BIGINT NOT NULL,
    reason TEXT NOT NULL,
    correlation_id UUID NOT NULL,
    occurred_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    fingerprint_version INTEGER NOT NULL,
    fingerprint TEXT NOT NULL,
    CONSTRAINT pk_inventory_movements PRIMARY KEY (tenant_id, operation_id),
    CONSTRAINT ck_inventory_movement_type CHECK (movement_type IN ('RECEIPT', 'ADJUSTMENT')),
    CONSTRAINT ck_inventory_movement_delta CHECK (
        delta <> 0 AND delta >= -9223372036854775807
        AND (movement_type <> 'RECEIPT' OR delta > 0)),
    CONSTRAINT ck_inventory_movement_reason CHECK (reason ~ '^[A-Z][A-Z0-9_]{0,63}$'),
    CONSTRAINT ck_inventory_movement_fingerprint CHECK (
        fingerprint_version = 1 AND fingerprint ~ '^[0-9a-f]{64}$')
);

-- Supports only the admitted bounded per-Variant administrative history query.
CREATE INDEX idx_inventory_movements_variant_operation
    ON inventory.movements (tenant_id, variant_id, operation_id);

CREATE TABLE inventory.policy_changes (
    tenant_id UUID NOT NULL,
    change_id UUID NOT NULL,
    actor_user_id UUID NOT NULL,
    variant_id UUID,
    action TEXT NOT NULL,
    before_safety_stock BIGINT,
    after_safety_stock BIGINT,
    before_policy TEXT,
    after_policy TEXT,
    reason TEXT NOT NULL,
    correlation_id UUID NOT NULL,
    occurred_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_inventory_policy_changes PRIMARY KEY (tenant_id, change_id),
    CONSTRAINT ck_inventory_policy_change_reason CHECK (reason ~ '^[A-Z][A-Z0-9_]{0,63}$'),
    CONSTRAINT ck_inventory_policy_change_shape CHECK (
        (action = 'SAFETY_STOCK' AND variant_id IS NOT NULL
            AND before_safety_stock IS NOT NULL AND before_safety_stock >= 0
            AND after_safety_stock IS NOT NULL AND after_safety_stock >= 0
            AND before_safety_stock <> after_safety_stock
            AND before_policy IS NULL AND after_policy IS NULL)
        OR
        (action = 'OVERSELL_POLICY' AND variant_id IS NULL
            AND before_safety_stock IS NULL AND after_safety_stock IS NULL
            AND (before_policy IS NULL OR before_policy IN ('DENY', 'ALLOW_BACKORDER'))
            AND after_policy IS NOT NULL AND after_policy IN ('DENY', 'ALLOW_BACKORDER')
            AND before_policy IS DISTINCT FROM after_policy))
);

CREATE FUNCTION inventory.reject_administration_evidence_mutation()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE check_violation USING
        MESSAGE = 'Inventory administration evidence is append-only',
        CONSTRAINT = 'ck_inventory_administration_evidence_append_only';
    RETURN NULL;
END;
$$;

CREATE TRIGGER trg_inventory_movements_append_only
    BEFORE UPDATE OR DELETE ON inventory.movements FOR EACH ROW
    EXECUTE FUNCTION inventory.reject_administration_evidence_mutation();
CREATE TRIGGER trg_inventory_movements_no_truncate
    BEFORE TRUNCATE ON inventory.movements FOR EACH STATEMENT
    EXECUTE FUNCTION inventory.reject_administration_evidence_mutation();
CREATE TRIGGER trg_inventory_policy_changes_append_only
    BEFORE UPDATE OR DELETE ON inventory.policy_changes FOR EACH ROW
    EXECUTE FUNCTION inventory.reject_administration_evidence_mutation();
CREATE TRIGGER trg_inventory_policy_changes_no_truncate
    BEFORE TRUNCATE ON inventory.policy_changes FOR EACH STATEMENT
    EXECUTE FUNCTION inventory.reject_administration_evidence_mutation();
