CREATE SCHEMA workforce;

CREATE TABLE workforce.staff_profiles (
    staff_id UUID NOT NULL,
    user_id UUID NOT NULL,
    tenant_id UUID NOT NULL,
    status TEXT NOT NULL,

    CONSTRAINT pk_workforce_staff_profiles
        PRIMARY KEY (staff_id),

    CONSTRAINT ck_workforce_staff_status
        CHECK (
            status IN (
                'ACTIVE',
                'INACTIVE'
            )
        ),

    CONSTRAINT uq_workforce_staff_user_tenant
        UNIQUE (
            user_id,
            tenant_id
        ),

    CONSTRAINT uq_workforce_staff_tenant_identity
        UNIQUE (
            tenant_id,
            staff_id
        )
);

CREATE TABLE workforce.departments (
    department_id UUID NOT NULL,
    tenant_id UUID NOT NULL,
    code TEXT NOT NULL,
    name TEXT NOT NULL,

    CONSTRAINT pk_workforce_departments
        PRIMARY KEY (department_id),

    CONSTRAINT ck_workforce_department_code
        CHECK (btrim(code) <> ''),

    CONSTRAINT ck_workforce_department_name
        CHECK (btrim(name) <> ''),

    CONSTRAINT uq_workforce_department_tenant_code
        UNIQUE (
            tenant_id,
            code
        ),

    CONSTRAINT uq_workforce_department_tenant_identity
        UNIQUE (
            tenant_id,
            department_id
        )
);

CREATE TABLE workforce.job_positions (
    position_id UUID NOT NULL,
    tenant_id UUID NOT NULL,
    code TEXT NOT NULL,
    title TEXT NOT NULL,
    authority_band TEXT NOT NULL,

    CONSTRAINT pk_workforce_job_positions
        PRIMARY KEY (position_id),

    CONSTRAINT ck_workforce_position_code
        CHECK (btrim(code) <> ''),

    CONSTRAINT ck_workforce_position_title
        CHECK (btrim(title) <> ''),

    CONSTRAINT ck_workforce_position_authority_band
        CHECK (
            authority_band IN (
                'OPERATIONAL',
                'SUPERVISORY',
                'COORDINATION',
                'MANAGEMENT',
                'TENANT_GOVERNANCE'
            )
        ),

    CONSTRAINT uq_workforce_position_tenant_code
        UNIQUE (
            tenant_id,
            code
        ),

    CONSTRAINT uq_workforce_position_tenant_identity
        UNIQUE (
            tenant_id,
            position_id
        )
);

CREATE TABLE workforce.job_position_permissions (
    tenant_id UUID NOT NULL,
    position_id UUID NOT NULL,
    permission_code TEXT NOT NULL,

    CONSTRAINT pk_workforce_job_position_permissions
        PRIMARY KEY (
            tenant_id,
            position_id,
            permission_code
        ),

    CONSTRAINT ck_workforce_position_permission_code
        CHECK (
            permission_code
                ~ '^[A-Z][A-Z0-9_]{2,63}$'
        ),

    CONSTRAINT fk_workforce_position_permission_position_scope
        FOREIGN KEY (
            tenant_id,
            position_id
        )
        REFERENCES workforce.job_positions (
            tenant_id,
            position_id
        )
);

CREATE TABLE workforce.staff_placements (
    tenant_id UUID NOT NULL,
    staff_id UUID NOT NULL,
    department_id UUID NOT NULL,
    position_id UUID NOT NULL,

    CONSTRAINT pk_workforce_staff_placements
        PRIMARY KEY (
            tenant_id,
            staff_id
        ),

    CONSTRAINT fk_workforce_placement_staff_scope
        FOREIGN KEY (
            tenant_id,
            staff_id
        )
        REFERENCES workforce.staff_profiles (
            tenant_id,
            staff_id
        ),

    CONSTRAINT fk_workforce_placement_department_scope
        FOREIGN KEY (
            tenant_id,
            department_id
        )
        REFERENCES workforce.departments (
            tenant_id,
            department_id
        ),

    CONSTRAINT fk_workforce_placement_position_scope
        FOREIGN KEY (
            tenant_id,
            position_id
        )
        REFERENCES workforce.job_positions (
            tenant_id,
            position_id
        )
);

CREATE TABLE workforce.reporting_relationships (
    tenant_id UUID NOT NULL,
    supervisor_staff_id UUID NOT NULL,
    subordinate_staff_id UUID NOT NULL,

    CONSTRAINT pk_workforce_reporting_relationships
        PRIMARY KEY (
            tenant_id,
            supervisor_staff_id,
            subordinate_staff_id
        ),

    CONSTRAINT ck_workforce_reporting_not_self
        CHECK (
            supervisor_staff_id
                <> subordinate_staff_id
        ),

    CONSTRAINT fk_workforce_reporting_supervisor_scope
        FOREIGN KEY (
            tenant_id,
            supervisor_staff_id
        )
        REFERENCES workforce.staff_profiles (
            tenant_id,
            staff_id
        ),

    CONSTRAINT fk_workforce_reporting_subordinate_scope
        FOREIGN KEY (
            tenant_id,
            subordinate_staff_id
        )
        REFERENCES workforce.staff_profiles (
            tenant_id,
            staff_id
        )
);

-- Deliberately no foreign keys from workforce into users.*, tenants.* or
-- access_control.*. Cross-module identity and permission-vocabulary validation
-- remains explicit at application/module boundaries.
--
-- Reporting-cycle arbitration, inactive-supervisor lifecycle handling,
-- last-governance concurrency and append-oriented audit persistence are not
-- introduced by this foundation migration.
