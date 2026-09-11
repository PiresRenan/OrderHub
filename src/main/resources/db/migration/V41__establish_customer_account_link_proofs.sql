-- Customer-owned capability; no Staff placement, role, contact or external identity state.
CREATE TABLE customers.account_link_proofs (
    proof_id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    customer_id UUID NOT NULL,
    issued_by_user_id UUID NOT NULL,
    operation_id UUID NOT NULL,
    correlation_id UUID NOT NULL,
    credential_digest BYTEA NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ,
    cancelled_at TIMESTAMPTZ,
    CONSTRAINT fk_customer_link_proof_profile FOREIGN KEY (tenant_id, customer_id)
        REFERENCES customers.customer_profiles (tenant_id, customer_id),
    CONSTRAINT uq_customer_link_proof_operation UNIQUE (tenant_id, operation_id),
    CONSTRAINT uq_customer_link_proof_digest UNIQUE (credential_digest),
    CONSTRAINT ck_customer_link_proof_digest CHECK (octet_length(credential_digest) = 32),
    CONSTRAINT ck_customer_link_proof_lifetime CHECK (
        expires_at > created_at AND expires_at <= created_at + INTERVAL '24 hours'),
    CONSTRAINT ck_customer_link_proof_terminal CHECK (consumed_at IS NULL OR cancelled_at IS NULL),
    CONSTRAINT ck_customer_link_proof_consumed_time CHECK (
        consumed_at IS NULL OR (consumed_at >= created_at AND consumed_at < expires_at)),
    CONSTRAINT ck_customer_link_proof_cancelled_time CHECK (
        cancelled_at IS NULL OR cancelled_at >= created_at)
);

-- Re-evaluate after obtaining the row lock, including a wait that crosses expiry.
CREATE FUNCTION customers.enforce_account_link_deadline()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.consumed_at IS NULL AND NEW.consumed_at IS NOT NULL
       AND clock_timestamp() >= OLD.expires_at THEN
        RETURN NULL;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_customer_link_deadline BEFORE UPDATE ON customers.account_link_proofs
    FOR EACH ROW EXECUTE FUNCTION customers.enforce_account_link_deadline();

CREATE TABLE customers.account_link_events (
    event_id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    customer_id UUID NOT NULL,
    proof_id UUID NOT NULL,
    actor_user_id UUID NOT NULL,
    subject_user_id UUID,
    action TEXT NOT NULL,
    correlation_id UUID NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_customer_link_event UNIQUE (proof_id, action),
    CONSTRAINT ck_customer_link_event_action CHECK (action IN ('ISSUED', 'CONSUMED', 'CANCELLED')),
    CONSTRAINT ck_customer_link_event_subject CHECK (
        (action = 'CONSUMED' AND subject_user_id IS NOT NULL)
        OR (action IN ('ISSUED', 'CANCELLED') AND subject_user_id IS NULL))
);

CREATE FUNCTION customers.reject_account_link_event_mutation()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE check_violation USING MESSAGE = 'Customer link evidence is append-only',
        CONSTRAINT = 'ck_customer_link_event_append_only';
    RETURN NULL;
END;
$$;
CREATE TRIGGER trg_customer_link_event_append_only BEFORE UPDATE OR DELETE ON customers.account_link_events
    FOR EACH ROW EXECUTE FUNCTION customers.reject_account_link_event_mutation();
CREATE TRIGGER trg_customer_link_event_no_truncate BEFORE TRUNCATE ON customers.account_link_events
    FOR EACH STATEMENT EXECUTE FUNCTION customers.reject_account_link_event_mutation();
