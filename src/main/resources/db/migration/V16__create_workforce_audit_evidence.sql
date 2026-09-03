CREATE TABLE workforce.audit_events (
    audit_event_id UUID NOT NULL,
    tenant_id UUID NOT NULL,
    actor_staff_id UUID NOT NULL,
    affected_staff_id UUID NOT NULL,
    action_type TEXT NOT NULL,
    outcome TEXT NOT NULL,
    reason_code TEXT,
    correlation_id UUID NOT NULL,
    before_status TEXT,
    after_status TEXT,
    before_department_id UUID,
    after_department_id UUID,
    before_position_id UUID,
    after_position_id UUID,
    before_supervisor_staff_id UUID,
    after_supervisor_staff_id UUID,
    before_authority_band TEXT,
    after_authority_band TEXT,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT pk_workforce_audit_events
        PRIMARY KEY (audit_event_id),

    CONSTRAINT ck_workforce_audit_action_type
        CHECK (
            action_type IN (
                'STAFF_ACTIVATED',
                'STAFF_DEACTIVATED',
                'DEPARTMENT_CHANGED',
                'POSITION_CHANGED',
                'POSITION_AUTHORITY_CHANGED',
                'SUPERVISOR_CHANGED',
                'PRIVILEGED_MUTATION'
            )
        ),

    CONSTRAINT ck_workforce_audit_outcome
        CHECK (
            outcome IN (
                'APPLIED',
                'DENIED'
            )
        ),

    CONSTRAINT ck_workforce_audit_reason_code
        CHECK (
            reason_code IS NULL
            OR reason_code ~ '^[A-Z][A-Z0-9_]{2,63}$'
        ),

    CONSTRAINT ck_workforce_audit_before_status
        CHECK (
            before_status IS NULL
            OR before_status IN (
                'ACTIVE',
                'INACTIVE'
            )
        ),

    CONSTRAINT ck_workforce_audit_after_status
        CHECK (
            after_status IS NULL
            OR after_status IN (
                'ACTIVE',
                'INACTIVE'
            )
        ),

    CONSTRAINT ck_workforce_audit_before_authority_band
        CHECK (
            before_authority_band IS NULL
            OR before_authority_band IN (
                'OPERATIONAL',
                'SUPERVISORY',
                'COORDINATION',
                'MANAGEMENT',
                'TENANT_GOVERNANCE'
            )
        ),

    CONSTRAINT ck_workforce_audit_after_authority_band
        CHECK (
            after_authority_band IS NULL
            OR after_authority_band IN (
                'OPERATIONAL',
                'SUPERVISORY',
                'COORDINATION',
                'MANAGEMENT',
                'TENANT_GOVERNANCE'
            )
        )
);

CREATE FUNCTION workforce.reject_audit_event_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE check_violation
        USING
            MESSAGE =
                'Workforce audit evidence is append-only',
            CONSTRAINT =
                'ck_workforce_audit_append_only';

    RETURN NULL;
END;
$$;

CREATE TRIGGER trg_workforce_audit_append_only
    BEFORE UPDATE OR DELETE
    ON workforce.audit_events
    FOR EACH ROW
    EXECUTE FUNCTION workforce.reject_audit_event_mutation();

-- Deliberately no foreign keys from audit evidence to mutable workforce rows.
-- Internal identifiers remain historical facts even when operational state
-- changes later.
--
-- The migration introduces no autonomous transaction mechanism. Audit writes
-- participate in the transaction established by the calling application
-- boundary.
