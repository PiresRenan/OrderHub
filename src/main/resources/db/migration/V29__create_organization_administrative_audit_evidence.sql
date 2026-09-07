CREATE TABLE organizations.administrative_audit_events (
    audit_event_id UUID PRIMARY KEY,
    actor_user_id UUID NOT NULL,
    organization_id UUID NOT NULL,
    tenant_id UUID,
    action_type TEXT NOT NULL,
    outcome TEXT NOT NULL,
    before_organization_status TEXT,
    after_organization_status TEXT,
    before_placement_organization_id UUID,
    after_placement_organization_id UUID,
    correlation_id UUID NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT ck_organization_administrative_audit_action
        CHECK (action_type IN (
            'CREATE_ORGANIZATION',
            'SUSPEND_ORGANIZATION',
            'RECOVER_ORGANIZATION',
            'ATTACH_TENANT',
            'MOVE_TENANT',
            'DETACH_TENANT'
        )),

    CONSTRAINT ck_organization_administrative_audit_outcome
        CHECK (outcome IN ('APPLIED', 'NO_CHANGE')),

    CONSTRAINT ck_organization_administrative_audit_status_vocabulary
        CHECK (
            before_organization_status IS NULL
            OR before_organization_status IN ('ACTIVE', 'SUSPENDED')
        ),

    CONSTRAINT ck_organization_administrative_audit_after_status_vocabulary
        CHECK (
            after_organization_status IS NULL
            OR after_organization_status IN ('ACTIVE', 'SUSPENDED')
        ),

    CONSTRAINT ck_organization_administrative_audit_shape
        CHECK (
            (
                action_type = 'CREATE_ORGANIZATION'
                AND tenant_id IS NULL
                AND before_organization_status IS NULL
                AND after_organization_status = 'ACTIVE'
                AND before_placement_organization_id IS NULL
                AND after_placement_organization_id IS NULL
                AND outcome = 'APPLIED'
            )
            OR
            (
                action_type IN ('SUSPEND_ORGANIZATION', 'RECOVER_ORGANIZATION')
                AND tenant_id IS NULL
                AND before_organization_status IS NOT NULL
                AND after_organization_status IS NOT NULL
                AND before_placement_organization_id IS NULL
                AND after_placement_organization_id IS NULL
                AND (
                    (outcome = 'APPLIED' AND before_organization_status <> after_organization_status)
                    OR
                    (outcome = 'NO_CHANGE' AND before_organization_status = after_organization_status)
                )
            )
            OR
            (
                action_type = 'ATTACH_TENANT'
                AND tenant_id IS NOT NULL
                AND before_organization_status IS NULL
                AND after_organization_status IS NULL
                AND (
                    (
                        outcome = 'APPLIED'
                        AND before_placement_organization_id IS NULL
                        AND after_placement_organization_id = organization_id
                    )
                    OR
                    (
                        outcome = 'NO_CHANGE'
                        AND before_placement_organization_id = organization_id
                        AND after_placement_organization_id = organization_id
                    )
                )
            )
            OR
            (
                action_type = 'MOVE_TENANT'
                AND tenant_id IS NOT NULL
                AND outcome = 'APPLIED'
                AND before_organization_status IS NULL
                AND after_organization_status IS NULL
                AND before_placement_organization_id IS NOT NULL
                AND after_placement_organization_id = organization_id
                AND before_placement_organization_id <> after_placement_organization_id
            )
            OR
            (
                action_type = 'DETACH_TENANT'
                AND tenant_id IS NOT NULL
                AND before_organization_status IS NULL
                AND after_organization_status IS NULL
                AND after_placement_organization_id IS NULL
                AND (
                    (outcome = 'APPLIED' AND before_placement_organization_id = organization_id)
                    OR
                    (outcome = 'NO_CHANGE' AND before_placement_organization_id IS NULL)
                )
            )
        )
);

CREATE FUNCTION organizations.reject_administrative_audit_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE check_violation
        USING
            MESSAGE = 'Organization administrative audit evidence is append-only',
            CONSTRAINT = 'ck_organization_administrative_audit_append_only';
    RETURN NULL;
END;
$$;

CREATE TRIGGER trg_organization_administrative_audit_append_only
    BEFORE UPDATE OR DELETE
    ON organizations.administrative_audit_events
    FOR EACH ROW
    EXECUTE FUNCTION organizations.reject_administrative_audit_mutation();

-- Historical owner-local evidence intentionally has no foreign keys.
