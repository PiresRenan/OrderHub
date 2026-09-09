-- A Staff invitee has no internal User and no external identity binding yet, so
-- the authorization to provision them must itself be durable. This relation is
-- workforce-owned because its subject is a prospective Staff relationship.
--
-- Only the SHA-256 digest of the one-time provisioning secret is stored. The
-- raw secret is returned once at creation and is never persisted, logged,
-- audited or emitted as a metric label.
--
-- Tenant, issuing User and role selector deliberately carry no cross-module
-- foreign key. Department and position use owner-local composite keys so a
-- provisioning intent can never freeze a placement from another Tenant.
--
-- Lifecycle is expressed only through timestamps. PENDING and EXPIRED remain
-- derived at evaluation time; no mutable status column exists.
CREATE TABLE workforce.staff_provisioning_intents (
    intent_id UUID NOT NULL,
    tenant_id UUID NOT NULL,
    secret_digest BYTEA NOT NULL,
    issued_by_user_id UUID NOT NULL,
    department_id UUID NOT NULL,
    position_id UUID NOT NULL,
    initial_role_code TEXT,
    operation_id UUID NOT NULL,
    request_fingerprint BYTEA NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ,
    cancelled_at TIMESTAMPTZ,
    correlation_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT pk_workforce_staff_provisioning_intents
        PRIMARY KEY (intent_id),

    -- The digest is the future single-use lookup identity, so one digest must
    -- never resolve to two provisioning authorizations. This also supplies the
    -- only index that exact-digest lookup requires.
    CONSTRAINT uq_workforce_provisioning_intent_secret_digest
        UNIQUE (secret_digest),

    -- A repeated creation operation in the same Tenant must not establish a
    -- second live credential. The database guarantees at most one row per
    -- operation scope; replay versus fingerprint conflict is decided above it.
    CONSTRAINT uq_workforce_provisioning_intent_operation
        UNIQUE (
            tenant_id,
            operation_id
        ),

    CONSTRAINT ck_workforce_provisioning_intent_secret_digest_length
        CHECK (
            octet_length(secret_digest) = 32
        ),

    CONSTRAINT ck_workforce_provisioning_intent_fingerprint_length
        CHECK (
            octet_length(request_fingerprint) = 32
        ),

    -- An opaque authorization selector. Role existence and delegation remain
    -- authorization-owned, so only the accepted code vocabulary is enforced.
    CONSTRAINT ck_workforce_provisioning_intent_role_code
        CHECK (
            initial_role_code IS NULL
            OR initial_role_code ~ '^[A-Z][A-Z0-9_]{2,63}$'
        ),

    CONSTRAINT ck_workforce_provisioning_intent_expiry
        CHECK (
            expires_at > created_at
        ),

    CONSTRAINT ck_workforce_provisioning_intent_terminal_exclusive
        CHECK (
            NOT (
                consumed_at IS NOT NULL
                AND cancelled_at IS NOT NULL
            )
        ),

    CONSTRAINT ck_workforce_provisioning_intent_consumed_after_creation
        CHECK (
            consumed_at IS NULL
            OR consumed_at >= created_at
        ),

    -- An expired authorization can never have been successfully consumed.
    CONSTRAINT ck_workforce_provisioning_intent_consumed_before_expiry
        CHECK (
            consumed_at IS NULL
            OR consumed_at < expires_at
        ),

    -- Cancellation is deliberately not bounded by expiry: an administrator may
    -- still need to revoke an unresolved intent after it has expired.
    CONSTRAINT ck_workforce_provisioning_intent_cancelled_after_creation
        CHECK (
            cancelled_at IS NULL
            OR cancelled_at >= created_at
        ),

    -- Owner-local composite references. No ON DELETE action is declared, so
    -- removing a department or position fails closed rather than silently
    -- discarding a pending or historical provisioning authorization.
    CONSTRAINT fk_workforce_provisioning_intent_department_scope
        FOREIGN KEY (
            tenant_id,
            department_id
        )
        REFERENCES workforce.departments (
            tenant_id,
            department_id
        ),

    CONSTRAINT fk_workforce_provisioning_intent_position_scope
        FOREIGN KEY (
            tenant_id,
            position_id
        )
        REFERENCES workforce.job_positions (
            tenant_id,
            position_id
        )
);
