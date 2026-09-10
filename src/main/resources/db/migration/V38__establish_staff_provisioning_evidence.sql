-- Owner-local evidence: Platform actors remain internal Users, never fabricated Staff.
-- No credential, digest, external identity or cross-module foreign key is stored.
CREATE TABLE workforce.provisioning_events (
    event_id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    intent_id UUID NOT NULL,
    actor_user_id UUID NOT NULL,
    subject_user_id UUID,
    staff_id UUID,
    action TEXT NOT NULL,
    correlation_id UUID NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_provisioning_event_action CHECK (
        action IN ('ISSUED', 'COLD_START_ISSUED', 'CONSUMED', 'CANCELLED')
    ),
    CONSTRAINT ck_provisioning_event_result CHECK (
        (action = 'CONSUMED' AND subject_user_id IS NOT NULL AND staff_id IS NOT NULL)
        OR (action IN ('ISSUED', 'COLD_START_ISSUED', 'CANCELLED')
            AND subject_user_id IS NULL AND staff_id IS NULL)
    ),
    CONSTRAINT uq_provisioning_event_action UNIQUE (intent_id, action)
);

CREATE UNIQUE INDEX uq_provisioning_event_issuance
    ON workforce.provisioning_events (intent_id)
    WHERE action IN ('ISSUED', 'COLD_START_ISSUED');

CREATE FUNCTION workforce.reject_provisioning_event_mutation()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE check_violation USING
        MESSAGE = 'Provisioning evidence is append-only',
        CONSTRAINT = 'ck_provisioning_event_append_only';
    RETURN NULL;
END;
$$;

CREATE TRIGGER trg_provisioning_event_append_only
    BEFORE UPDATE OR DELETE ON workforce.provisioning_events
    FOR EACH ROW EXECUTE FUNCTION workforce.reject_provisioning_event_mutation();

CREATE TRIGGER trg_provisioning_event_no_truncate
    BEFORE TRUNCATE ON workforce.provisioning_events
    FOR EACH STATEMENT EXECUTE FUNCTION workforce.reject_provisioning_event_mutation();
