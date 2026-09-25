-- OH-024 / ADR-0022: retained first-operator bootstrap ceremony.
--
-- One singleton row is the PostgreSQL arbitration point for every bootstrap
-- attempt on every process: attempts lock it FOR UPDATE and may move it only
-- from OPEN to COMPLETED. No issuer, subject, credential or User is seeded;
-- the OPEN row carries no identity at all.
--
-- request_fingerprint is the immutable replay identity of the completing
-- request: lowercase hex SHA-256 over a versioned domain, the operation id and
-- the length-prefixed exact issuer and subject. It is neither a credential nor
-- an authority, and raw issuer/subject are never stored here.

CREATE SCHEMA bootstrap;

CREATE TABLE bootstrap.first_operator_ceremony (
    ceremony TEXT NOT NULL,
    state TEXT NOT NULL,
    operation_id UUID,
    operator_user_id UUID,
    request_fingerprint TEXT,
    completed_at TIMESTAMPTZ,

    CONSTRAINT pk_bootstrap_first_operator_ceremony
        PRIMARY KEY (ceremony),

    CONSTRAINT ck_bootstrap_first_operator_ceremony_singleton
        CHECK (ceremony = 'RETAINED_FIRST_OPERATOR_BOOTSTRAP'),

    CONSTRAINT ck_bootstrap_first_operator_ceremony_state
        CHECK (state IN ('OPEN', 'COMPLETED')),

    CONSTRAINT ck_bootstrap_first_operator_ceremony_shape
        CHECK (
            (
                state = 'OPEN'
                AND operation_id IS NULL
                AND operator_user_id IS NULL
                AND request_fingerprint IS NULL
                AND completed_at IS NULL
            )
            OR
            (
                state = 'COMPLETED'
                AND operation_id IS NOT NULL
                AND operator_user_id IS NOT NULL
                AND request_fingerprint IS NOT NULL
                AND completed_at IS NOT NULL
            )
        ),

    CONSTRAINT ck_bootstrap_first_operator_ceremony_fingerprint
        CHECK (request_fingerprint ~ '^[0-9a-f]{64}$')
);

INSERT INTO bootstrap.first_operator_ceremony (ceremony, state)
VALUES ('RETAINED_FIRST_OPERATOR_BOOTSTRAP', 'OPEN');

-- The only legal transition is OPEN -> COMPLETED. There is no reset, reopen,
-- delete or truncate: recovery is a separate, stronger ceremony.
CREATE FUNCTION bootstrap.guard_first_operator_ceremony()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'UPDATE'
            AND OLD.state = 'OPEN'
            AND NEW.state = 'COMPLETED'
            AND NEW.ceremony = OLD.ceremony THEN
        RETURN NEW;
    END IF;

    RAISE check_violation
        USING
            MESSAGE = 'First-operator bootstrap state only moves from OPEN to COMPLETED',
            CONSTRAINT = 'ck_bootstrap_first_operator_ceremony_forward_only';

    RETURN NULL;
END;
$$;

CREATE TRIGGER trg_bootstrap_first_operator_ceremony_forward_only
    BEFORE INSERT OR UPDATE OR DELETE
    ON bootstrap.first_operator_ceremony
    FOR EACH ROW
    EXECUTE FUNCTION bootstrap.guard_first_operator_ceremony();

CREATE TABLE bootstrap.first_operator_ceremony_events (
    event_id UUID NOT NULL,
    ceremony TEXT NOT NULL,
    operation_id UUID NOT NULL,
    outcome TEXT NOT NULL,
    operator_user_id UUID NOT NULL,
    granted_permission TEXT NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT pk_bootstrap_first_operator_ceremony_events
        PRIMARY KEY (event_id),

    CONSTRAINT fk_bootstrap_first_operator_ceremony_events_ceremony
        FOREIGN KEY (ceremony)
        REFERENCES bootstrap.first_operator_ceremony (ceremony),

    CONSTRAINT ck_bootstrap_first_operator_ceremony_events_outcome
        CHECK (outcome = 'COMPLETED'),

    CONSTRAINT ck_bootstrap_first_operator_ceremony_events_permission
        CHECK (granted_permission = 'PLATFORM_TENANTS_MANAGE'),

    -- At most one privileged success transition can ever be recorded.
    CONSTRAINT uq_bootstrap_first_operator_ceremony_events_success
        UNIQUE (ceremony, outcome)
);

CREATE FUNCTION bootstrap.reject_first_operator_ceremony_event_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE check_violation
        USING
            MESSAGE = 'First-operator bootstrap evidence is append-only',
            CONSTRAINT = 'ck_bootstrap_first_operator_ceremony_events_append_only';

    RETURN NULL;
END;
$$;

CREATE TRIGGER trg_bootstrap_first_operator_ceremony_events_append_only
    BEFORE UPDATE OR DELETE
    ON bootstrap.first_operator_ceremony_events
    FOR EACH ROW
    EXECUTE FUNCTION bootstrap.reject_first_operator_ceremony_event_mutation();

CREATE TRIGGER trg_bootstrap_first_operator_ceremony_truncate
    BEFORE TRUNCATE
    ON bootstrap.first_operator_ceremony
    FOR EACH STATEMENT
    EXECUTE FUNCTION bootstrap.guard_first_operator_ceremony();

CREATE TRIGGER trg_bootstrap_first_operator_ceremony_events_truncate
    BEFORE TRUNCATE
    ON bootstrap.first_operator_ceremony_events
    FOR EACH STATEMENT
    EXECUTE FUNCTION bootstrap.reject_first_operator_ceremony_event_mutation();

-- Deliberately no foreign keys into users or access_control: this is
-- bootstrap-owned evidence, and the append adapter owns no transaction.
