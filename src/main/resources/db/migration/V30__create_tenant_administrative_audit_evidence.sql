CREATE TABLE tenants.administrative_audit_events (
    audit_event_id UUID PRIMARY KEY,
    actor_user_id UUID NOT NULL,
    tenant_id UUID NOT NULL,
    action_type TEXT NOT NULL,
    outcome TEXT NOT NULL,
    before_status TEXT,
    after_status TEXT NOT NULL,
    correlation_id UUID NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT ck_tenant_administrative_audit_action
        CHECK (action_type IN (
            'CREATE_TENANT',
            'SUSPEND_TENANT',
            'RECOVER_TENANT'
        )),

    CONSTRAINT ck_tenant_administrative_audit_outcome
        CHECK (outcome IN ('APPLIED', 'NO_CHANGE')),

    CONSTRAINT ck_tenant_administrative_audit_status_vocabulary
        CHECK (
            (before_status IS NULL OR before_status IN ('ACTIVE', 'SUSPENDED'))
            AND after_status IN ('ACTIVE', 'SUSPENDED')
        ),

    CONSTRAINT ck_tenant_administrative_audit_shape
        CHECK (
            (
                action_type = 'CREATE_TENANT'
                AND outcome = 'APPLIED'
                AND before_status IS NULL
                AND after_status = 'ACTIVE'
            )
            OR
            (
                action_type IN ('SUSPEND_TENANT', 'RECOVER_TENANT')
                AND before_status IS NOT NULL
                AND (
                    (outcome = 'APPLIED' AND before_status <> after_status)
                    OR
                    (outcome = 'NO_CHANGE' AND before_status = after_status)
                )
            )
        )
);

CREATE FUNCTION tenants.reject_administrative_audit_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE check_violation
        USING
            MESSAGE = 'Tenant administrative audit evidence is append-only',
            CONSTRAINT = 'ck_tenant_administrative_audit_append_only';
    RETURN NULL;
END;
$$;

CREATE TRIGGER trg_tenant_administrative_audit_append_only
    BEFORE UPDATE OR DELETE
    ON tenants.administrative_audit_events
    FOR EACH ROW
    EXECUTE FUNCTION tenants.reject_administrative_audit_mutation();

-- Historical owner-local evidence intentionally has no foreign keys.
