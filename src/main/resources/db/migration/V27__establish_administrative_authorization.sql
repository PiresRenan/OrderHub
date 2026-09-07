ALTER TABLE access_control.permissions
    ADD COLUMN administrative_scope TEXT;

ALTER TABLE access_control.permissions
    ALTER COLUMN persona DROP NOT NULL;

ALTER TABLE access_control.permissions
    DROP CONSTRAINT ck_authorization_permission_persona;

ALTER TABLE access_control.permissions
    ADD CONSTRAINT ck_authorization_permission_persona
        CHECK (
            persona IS NULL
            OR persona IN (
                'STAFF',
                'CUSTOMER'
            )
        );

ALTER TABLE access_control.permissions
    ADD CONSTRAINT ck_authorization_permission_classification
        CHECK (
            (
                persona IN (
                    'STAFF',
                    'CUSTOMER'
                )
                AND administrative_scope IS NULL
            )
            OR
            (
                persona IS NULL
                AND administrative_scope IN (
                    'PLATFORM',
                    'ORGANIZATION',
                    'TENANT'
                )
            )
        );

CREATE FUNCTION access_control.enforce_permission_administrative_scope_immutability()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.administrative_scope IS DISTINCT FROM NEW.administrative_scope THEN

        RAISE check_violation
            USING
                MESSAGE =
                    'Permission administrative scope classification is immutable',
                CONSTRAINT =
                    'ck_authorization_permission_administrative_scope_immutable';
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_authorization_permission_administrative_scope_immutability
BEFORE UPDATE OF administrative_scope
ON access_control.permissions
FOR EACH ROW
EXECUTE FUNCTION access_control.enforce_permission_administrative_scope_immutability();

INSERT INTO access_control.permissions (
    code,
    persona,
    administrative_scope
)
VALUES
    (
        'PLATFORM_ORGANIZATIONS_VIEW',
        NULL,
        'PLATFORM'
    ),
    (
        'PLATFORM_ORGANIZATIONS_MANAGE',
        NULL,
        'PLATFORM'
    ),
    (
        'PLATFORM_TENANTS_MANAGE',
        NULL,
        'PLATFORM'
    ),
    (
        'PLATFORM_ORGANIZATION_GRANTS_MANAGE',
        NULL,
        'PLATFORM'
    ),
    (
        'ORGANIZATION_TENANTS_VIEW',
        NULL,
        'ORGANIZATION'
    );

CREATE TABLE access_control.administrative_grants (
    grant_id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    scope_type TEXT NOT NULL,
    scope_id UUID,
    permission_code TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT ck_authorization_administrative_grant_scope_type
        CHECK (
            scope_type IN (
                'PLATFORM',
                'ORGANIZATION',
                'TENANT'
            )
        ),

    CONSTRAINT ck_authorization_administrative_grant_scope_shape
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

    CONSTRAINT fk_authorization_administrative_grant_permission
        FOREIGN KEY (permission_code)
        REFERENCES access_control.permissions (code),

    CONSTRAINT uq_authorization_administrative_grant_identity
        UNIQUE NULLS NOT DISTINCT (
            user_id,
            scope_type,
            scope_id,
            permission_code
        )
);

CREATE FUNCTION access_control.enforce_administrative_grant_permission_scope()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    permission_scope TEXT;
BEGIN
    SELECT permission.administrative_scope
    INTO permission_scope
    FROM access_control.permissions permission
    WHERE permission.code = NEW.permission_code;

    IF NOT FOUND THEN
        RETURN NEW;
    END IF;

    IF permission_scope IS DISTINCT FROM NEW.scope_type THEN

        RAISE check_violation
            USING
                MESSAGE =
                    'Administrative grant permission and scope must be compatible',
                CONSTRAINT =
                    'ck_authorization_administrative_grant_permission_scope';
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_authorization_administrative_grant_permission_scope
BEFORE INSERT OR UPDATE OF
    scope_type,
    permission_code
ON access_control.administrative_grants
FOR EACH ROW
EXECUTE FUNCTION access_control.enforce_administrative_grant_permission_scope();

CREATE FUNCTION access_control.enforce_tenant_override_non_administrative_permission()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    permission_scope TEXT;
BEGIN
    SELECT permission.administrative_scope
    INTO permission_scope
    FROM access_control.permissions permission
    WHERE permission.code = NEW.permission_code;

    IF NOT FOUND THEN
        RETURN NEW;
    END IF;

    IF permission_scope IS NOT NULL THEN

        RAISE check_violation
            USING
                MESSAGE =
                    'Administrative permission cannot be used as Tenant permission override',
                CONSTRAINT =
                    'ck_authorization_override_non_administrative_permission';
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_authorization_override_non_administrative_permission
BEFORE INSERT OR UPDATE OF permission_code
ON access_control.user_permission_overrides
FOR EACH ROW
EXECUTE FUNCTION access_control.enforce_tenant_override_non_administrative_permission();

-- Deliberately no foreign keys into users.*, organizations.* or tenants.*.
-- Cross-module target existence remains an application-contract concern.
