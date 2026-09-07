CREATE TABLE access_control.administrative_grant_audit_events (
    audit_event_id UUID NOT NULL,
    actor_user_id UUID NOT NULL,
    target_user_id UUID NOT NULL,
    scope_type TEXT NOT NULL,
    scope_id UUID,
    permission_code TEXT NOT NULL,
    action_type TEXT NOT NULL,
    outcome TEXT NOT NULL,
    correlation_id UUID NOT NULL,
    before_granted BOOLEAN NOT NULL,
    after_granted BOOLEAN NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT pk_authorization_administrative_grant_audit_events
        PRIMARY KEY (audit_event_id),

    CONSTRAINT ck_authorization_administrative_grant_audit_scope_type
        CHECK (
            scope_type IN (
                'PLATFORM',
                'ORGANIZATION',
                'TENANT'
            )
        ),

    CONSTRAINT ck_authorization_administrative_grant_audit_scope_shape
        CHECK (
            (
                scope_type = 'PLATFORM'
                AND scope_id IS NULL
            )
            OR
            (
                scope_type IN (
                    'ORGANIZATION',
                    'TENANT'
                )
                AND scope_id IS NOT NULL
            )
        ),

    CONSTRAINT ck_authorization_administrative_grant_audit_permission
        CHECK (
            permission_code IN (
                'PLATFORM_ORGANIZATIONS_VIEW',
                'PLATFORM_ORGANIZATIONS_MANAGE',
                'PLATFORM_TENANTS_MANAGE',
                'PLATFORM_ORGANIZATION_GRANTS_MANAGE',
                'ORGANIZATION_TENANTS_VIEW'
            )
        ),

    CONSTRAINT ck_authorization_administrative_grant_audit_permission_scope
        CHECK (
            (
                scope_type = 'PLATFORM'
                AND permission_code IN (
                    'PLATFORM_ORGANIZATIONS_VIEW',
                    'PLATFORM_ORGANIZATIONS_MANAGE',
                    'PLATFORM_TENANTS_MANAGE',
                    'PLATFORM_ORGANIZATION_GRANTS_MANAGE'
                )
            )
            OR
            (
                scope_type = 'ORGANIZATION'
                AND permission_code = 'ORGANIZATION_TENANTS_VIEW'
            )
        ),

    CONSTRAINT ck_authorization_administrative_grant_audit_action
        CHECK (
            action_type IN (
                'GRANT_PERMISSION',
                'REVOKE_PERMISSION'
            )
        ),

    CONSTRAINT ck_authorization_administrative_grant_audit_outcome
        CHECK (
            outcome IN (
                'APPLIED',
                'NO_CHANGE'
            )
        ),

    CONSTRAINT ck_authorization_administrative_grant_audit_action_state
        CHECK (
            (
                action_type = 'GRANT_PERMISSION'
                AND after_granted
            )
            OR
            (
                action_type = 'REVOKE_PERMISSION'
                AND NOT after_granted
            )
        ),

    CONSTRAINT ck_authorization_administrative_grant_audit_outcome_state
        CHECK (
            (
                outcome = 'APPLIED'
                AND before_granted <> after_granted
            )
            OR
            (
                outcome = 'NO_CHANGE'
                AND before_granted = after_granted
            )
        )
);

CREATE FUNCTION access_control.reject_administrative_grant_audit_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE check_violation
        USING
            MESSAGE =
                'Administrative grant audit evidence is append-only',
            CONSTRAINT =
                'ck_authorization_administrative_grant_audit_append_only';

    RETURN NULL;
END;
$$;

CREATE TRIGGER trg_authorization_administrative_grant_audit_append_only
    BEFORE UPDATE OR DELETE
    ON access_control.administrative_grant_audit_events
    FOR EACH ROW
    EXECUTE FUNCTION access_control.reject_administrative_grant_audit_mutation();

-- Deliberately no foreign keys from historical administrative audit evidence
-- into users, organizations, tenants or the mutable administrative grant row.
-- The append adapter owns no independent transaction boundary.
