CREATE TABLE users.tenant_membership_events (
    event_id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    actor_user_id UUID NOT NULL,
    subject_user_id UUID NOT NULL,
    action TEXT NOT NULL,
    before_status TEXT NOT NULL,
    after_status TEXT NOT NULL,
    correlation_id UUID NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_membership_event_transition CHECK (
        (action = 'SUSPEND' AND before_status = 'ACTIVE' AND after_status = 'SUSPENDED')
        OR (action = 'RECOVER' AND before_status = 'SUSPENDED' AND after_status = 'ACTIVE')
        OR (action = 'TERMINATE' AND before_status IN ('ACTIVE', 'SUSPENDED') AND after_status = 'TERMINATED')),
    CONSTRAINT ck_membership_event_independent_actor CHECK (actor_user_id <> subject_user_id)
);

CREATE FUNCTION users.reject_tenant_membership_event_mutation()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE check_violation USING MESSAGE = 'Membership evidence is append-only',
        CONSTRAINT = 'ck_membership_event_append_only';
    RETURN NULL;
END;
$$;
CREATE TRIGGER trg_membership_event_append_only BEFORE UPDATE OR DELETE ON users.tenant_membership_events
    FOR EACH ROW EXECUTE FUNCTION users.reject_tenant_membership_event_mutation();
CREATE TRIGGER trg_membership_event_no_truncate BEFORE TRUNCATE ON users.tenant_membership_events
    FOR EACH STATEMENT EXECUTE FUNCTION users.reject_tenant_membership_event_mutation();
