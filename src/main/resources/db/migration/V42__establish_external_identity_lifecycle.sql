ALTER TABLE users.external_identity_bindings
    ADD COLUMN binding_id UUID NOT NULL DEFAULT gen_random_uuid(),
    ADD COLUMN active BOOLEAN NOT NULL DEFAULT TRUE,
    ADD CONSTRAINT uq_external_identity_binding_id UNIQUE (binding_id);

CREATE INDEX ix_external_identity_bindings_user ON users.external_identity_bindings (user_id, binding_id);

-- A revoked pair keeps its original owner. Only explicit lifecycle activity can change.
CREATE FUNCTION users.preserve_external_identity_owner()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF (NEW.issuer, NEW.subject, NEW.user_id, NEW.binding_id)
       IS DISTINCT FROM (OLD.issuer, OLD.subject, OLD.user_id, OLD.binding_id) THEN
        RAISE check_violation USING MESSAGE = 'External identity ownership is immutable',
            CONSTRAINT = 'ck_external_identity_owner_immutable';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_external_identity_owner_immutable BEFORE UPDATE ON users.external_identity_bindings
    FOR EACH ROW EXECUTE FUNCTION users.preserve_external_identity_owner();

CREATE TABLE users.external_identity_link_proofs (
    proof_id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users.users(id),
    operation_id UUID NOT NULL,
    correlation_id UUID NOT NULL,
    credential_digest BYTEA NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ,
    cancelled_at TIMESTAMPTZ,
    CONSTRAINT uq_external_identity_link_operation UNIQUE (user_id, operation_id),
    CONSTRAINT uq_external_identity_link_digest UNIQUE (credential_digest),
    CONSTRAINT ck_external_identity_link_digest CHECK (octet_length(credential_digest) = 32),
    CONSTRAINT ck_external_identity_link_lifetime CHECK (expires_at > created_at AND expires_at <= created_at + INTERVAL '15 minutes'),
    CONSTRAINT ck_external_identity_link_terminal CHECK (consumed_at IS NULL OR cancelled_at IS NULL),
    CONSTRAINT ck_external_identity_link_consumed CHECK (consumed_at IS NULL OR (consumed_at >= created_at AND consumed_at < expires_at)),
    CONSTRAINT ck_external_identity_link_cancelled CHECK (cancelled_at IS NULL OR cancelled_at >= created_at)
);

CREATE FUNCTION users.enforce_external_identity_link_deadline()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.consumed_at IS NULL AND NEW.consumed_at IS NOT NULL AND clock_timestamp() >= OLD.expires_at THEN
        RETURN NULL;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_external_identity_link_deadline BEFORE UPDATE ON users.external_identity_link_proofs
    FOR EACH ROW EXECUTE FUNCTION users.enforce_external_identity_link_deadline();

CREATE TABLE users.external_identity_events (
    event_id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    binding_id UUID,
    proof_id UUID,
    action TEXT NOT NULL,
    changed BOOLEAN NOT NULL,
    correlation_id UUID NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_external_identity_proof_event UNIQUE (proof_id, action),
    CONSTRAINT ck_external_identity_event_action CHECK (action IN ('ISSUED', 'LINKED', 'UNLINKED', 'CANCELLED')),
    CONSTRAINT ck_external_identity_event_shape CHECK (
        (action IN ('ISSUED', 'CANCELLED') AND proof_id IS NOT NULL AND binding_id IS NULL)
        OR (action = 'LINKED' AND proof_id IS NOT NULL AND binding_id IS NOT NULL)
        OR (action = 'UNLINKED' AND proof_id IS NULL AND binding_id IS NOT NULL))
);
CREATE FUNCTION users.reject_external_identity_event_mutation()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE check_violation USING MESSAGE = 'External identity evidence is append-only',
        CONSTRAINT = 'ck_external_identity_event_append_only';
    RETURN NULL;
END;
$$;
CREATE TRIGGER trg_external_identity_event_append_only BEFORE UPDATE OR DELETE ON users.external_identity_events
    FOR EACH ROW EXECUTE FUNCTION users.reject_external_identity_event_mutation();
CREATE TRIGGER trg_external_identity_event_no_truncate BEFORE TRUNCATE ON users.external_identity_events
    FOR EACH STATEMENT EXECUTE FUNCTION users.reject_external_identity_event_mutation();
