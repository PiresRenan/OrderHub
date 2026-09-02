CREATE SCHEMA access_control;

CREATE TABLE access_control.permissions (
    code TEXT PRIMARY KEY,
    persona TEXT NOT NULL,

    CONSTRAINT ck_authorization_permission_code
        CHECK (code ~ '^[A-Z][A-Z0-9_]{2,63}$'),

    CONSTRAINT ck_authorization_permission_persona
        CHECK (persona IN ('STAFF', 'CUSTOMER'))
);

INSERT INTO access_control.permissions (
    code,
    persona
)
VALUES
    ('TENANT_MEMBERS_VIEW', 'STAFF'),
    ('TENANT_MEMBERS_MANAGE', 'STAFF'),
    ('TENANT_ROLES_VIEW', 'STAFF'),
    ('TENANT_ROLES_ASSIGN', 'STAFF'),
    ('TENANT_PRIVILEGED_ROLES_ASSIGN', 'STAFF'),

    ('CATALOG_VIEW', 'STAFF'),
    ('CATALOG_MANAGE', 'STAFF'),
    ('CATALOG_PRICE_MANAGE', 'STAFF'),

    ('INVENTORY_VIEW', 'STAFF'),
    ('INVENTORY_RECEIVE', 'STAFF'),
    ('INVENTORY_ADJUST', 'STAFF'),
    ('INVENTORY_POLICY_MANAGE', 'STAFF'),

    ('ORDERS_VIEW', 'STAFF'),
    ('ORDERS_CREATE', 'STAFF'),
    ('ORDERS_MANAGE', 'STAFF'),
    ('ORDERS_APPROVE', 'STAFF'),

    ('AUDIT_VIEW', 'STAFF');

CREATE TABLE access_control.role_code_registry (
    code TEXT NOT NULL,
    role_namespace TEXT NOT NULL,

    CONSTRAINT pk_authorization_role_code_registry
        PRIMARY KEY (code),

    CONSTRAINT ck_authorization_role_code_registry_code
        CHECK (code ~ '^[A-Z][A-Z0-9_]{2,63}$'),

    CONSTRAINT ck_authorization_role_code_registry_namespace
        CHECK (
            role_namespace IN (
                'SYSTEM',
                'TENANT_CUSTOM'
            )
        )
);
CREATE TABLE access_control.role_definitions (
    role_id UUID PRIMARY KEY,
    tenant_id UUID,
    code TEXT NOT NULL,
    persona TEXT NOT NULL,
    authority_band TEXT NOT NULL,
    mutability TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT ck_authorization_role_code
        CHECK (code ~ '^[A-Z][A-Z0-9_]{2,63}$'),

    CONSTRAINT ck_authorization_role_persona
        CHECK (persona = 'STAFF'),

    CONSTRAINT ck_authorization_role_authority_band
        CHECK (
            authority_band IN (
                'OPERATIONAL',
                'SUPERVISORY',
                'COORDINATION',
                'MANAGEMENT',
                'TENANT_GOVERNANCE'
            )
        ),

    CONSTRAINT ck_authorization_role_mutability
        CHECK (
            mutability IN (
                'SYSTEM_LOCKED',
                'TENANT_PROTECTED',
                'BUILTIN_FUNCTIONAL',
                'TENANT_CUSTOM'
            )
        ),

    CONSTRAINT ck_authorization_role_ownership
        CHECK (
            (
                mutability = 'TENANT_CUSTOM'
                AND tenant_id IS NOT NULL
            )
            OR
            (
                mutability <> 'TENANT_CUSTOM'
                AND tenant_id IS NULL
            )
        )
);

CREATE UNIQUE INDEX uq_authorization_system_role_code
    ON access_control.role_definitions (code)
    WHERE tenant_id IS NULL;

CREATE UNIQUE INDEX uq_authorization_tenant_role_code
    ON access_control.role_definitions (
        tenant_id,
        code
    )
    WHERE tenant_id IS NOT NULL;

CREATE FUNCTION access_control.reserve_role_code_namespace()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    requested_namespace TEXT;
    reserved_namespace TEXT;
BEGIN
    requested_namespace :=
        CASE
            WHEN NEW.mutability = 'TENANT_CUSTOM'
                THEN 'TENANT_CUSTOM'
            ELSE 'SYSTEM'
        END;

    INSERT INTO access_control.role_code_registry (
        code,
        role_namespace
    )
    VALUES (
        NEW.code,
        requested_namespace
    )
    ON CONFLICT (code)
    DO NOTHING
    RETURNING role_namespace
    INTO reserved_namespace;

    IF reserved_namespace IS NULL THEN
        SELECT registry.role_namespace
        INTO reserved_namespace
        FROM access_control.role_code_registry registry
        WHERE registry.code = NEW.code;
    END IF;

    IF reserved_namespace IS DISTINCT FROM requested_namespace THEN
        RAISE unique_violation
            USING
                MESSAGE =
                    'Role code is already reserved by another authorization namespace',
                CONSTRAINT =
                    'pk_authorization_role_code_registry';
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_authorization_role_code_namespace
BEFORE INSERT OR UPDATE OF
    code,
    tenant_id,
    mutability
ON access_control.role_definitions
FOR EACH ROW
EXECUTE FUNCTION access_control.reserve_role_code_namespace();
CREATE TABLE access_control.role_permissions (
    role_id UUID NOT NULL,
    permission_code TEXT NOT NULL,

    CONSTRAINT pk_authorization_role_permissions
        PRIMARY KEY (
            role_id,
            permission_code
        ),

    CONSTRAINT fk_authorization_role_permissions_role
        FOREIGN KEY (role_id)
        REFERENCES access_control.role_definitions (role_id)
        ON DELETE CASCADE,

    CONSTRAINT fk_authorization_role_permissions_permission
        FOREIGN KEY (permission_code)
        REFERENCES access_control.permissions (code)
);

CREATE TABLE access_control.role_assignments (
    assignment_id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    tenant_id UUID NOT NULL,
    persona TEXT NOT NULL,
    role_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT ck_authorization_assignment_persona
        CHECK (persona = 'STAFF'),

    CONSTRAINT fk_authorization_assignment_role
        FOREIGN KEY (role_id)
        REFERENCES access_control.role_definitions (role_id),

    CONSTRAINT uq_authorization_role_assignment
        UNIQUE (
            user_id,
            tenant_id,
            persona,
            role_id
        )
);

CREATE FUNCTION access_control.enforce_role_assignment_scope()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    role_tenant_id UUID;
BEGIN
    SELECT role.tenant_id
    INTO role_tenant_id
    FROM access_control.role_definitions role
    WHERE role.role_id = NEW.role_id;

    IF NOT FOUND THEN
        RETURN NEW;
    END IF;

    IF role_tenant_id IS NOT NULL
            AND role_tenant_id <> NEW.tenant_id THEN

        RAISE check_violation
            USING
                MESSAGE =
                    'Tenant-owned role assignment must use the role owning Tenant scope',
                CONSTRAINT =
                    'ck_authorization_role_assignment_scope';
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_authorization_role_assignment_scope
BEFORE INSERT OR UPDATE OF
    tenant_id,
    role_id
ON access_control.role_assignments
FOR EACH ROW
EXECUTE FUNCTION access_control.enforce_role_assignment_scope();
CREATE INDEX idx_authorization_role_assignments_subject_scope
    ON access_control.role_assignments (
        tenant_id,
        user_id
    );

CREATE TABLE access_control.user_permission_overrides (
    override_id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    tenant_id UUID NOT NULL,
    permission_code TEXT NOT NULL,
    effect TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT ck_authorization_override_effect
        CHECK (effect IN ('ALLOW', 'DENY')),

    CONSTRAINT fk_authorization_override_permission
        FOREIGN KEY (permission_code)
        REFERENCES access_control.permissions (code),

    CONSTRAINT uq_authorization_user_permission_override
        UNIQUE (
            user_id,
            tenant_id,
            permission_code
        )
);

CREATE INDEX idx_authorization_permission_overrides_subject_scope
    ON access_control.user_permission_overrides (
        tenant_id,
        user_id
    );

-- Deliberately no foreign keys into users.* or tenants.*.
-- Cross-module existence and membership checks belong to explicit
-- application contracts rather than relational coupling between modules.
