-- Existing role/override writers must participate in provisioning arbitration.
-- Locks are per internal User/Tenant and last only for the physical transaction.
CREATE FUNCTION access_control.acquire_staff_provisioning_authority_lock(
    requested_user_id UUID, requested_tenant_id UUID
)
RETURNS VOID LANGUAGE plpgsql AS $$
BEGIN
    IF requested_user_id IS NULL OR requested_tenant_id IS NULL THEN
        RAISE check_violation USING MESSAGE = 'Provisioning authority scope is required';
    END IF;
    PERFORM pg_advisory_xact_lock(hashtextextended(
        'orderhub.authorization.staff-provisioning:' || requested_tenant_id::TEXT || ':' || requested_user_id::TEXT, 0));
END;
$$;

CREATE FUNCTION access_control.stabilize_staff_provisioning_authority()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    previous_scope TEXT;
    next_scope TEXT;
    locked_scope TEXT;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        previous_scope := OLD.tenant_id::TEXT || ':' || OLD.user_id::TEXT;
    END IF;
    IF TG_OP <> 'DELETE' THEN
        next_scope := NEW.tenant_id::TEXT || ':' || NEW.user_id::TEXT;
    END IF;
    FOR locked_scope IN
        SELECT scope FROM (VALUES (previous_scope), (next_scope)) scopes(scope)
        WHERE scope IS NOT NULL GROUP BY scope ORDER BY scope
    LOOP
        PERFORM pg_advisory_xact_lock(hashtextextended(
            'orderhub.authorization.staff-provisioning:' || locked_scope, 0));
    END LOOP;
    IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_staff_provisioning_assignment_scope
    BEFORE INSERT OR UPDATE OR DELETE ON access_control.role_assignments
    FOR EACH ROW EXECUTE FUNCTION access_control.stabilize_staff_provisioning_authority();

CREATE TRIGGER trg_staff_provisioning_override_scope
    BEFORE INSERT OR UPDATE OR DELETE ON access_control.user_permission_overrides
    FOR EACH ROW EXECUTE FUNCTION access_control.stabilize_staff_provisioning_authority();

CREATE TABLE access_control.staff_provisioning_role_events (
    event_id UUID PRIMARY KEY,
    actor_user_id UUID NOT NULL,
    tenant_id UUID NOT NULL,
    target_user_id UUID NOT NULL,
    role_code TEXT NOT NULL CHECK (role_code ~ '^[A-Z][A-Z0-9_]{2,63}$'),
    intent_id UUID NOT NULL UNIQUE,
    correlation_id UUID NOT NULL,
    changed BOOLEAN NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- No FK reaches mutable role, identity, workforce or intent state.
CREATE FUNCTION access_control.reject_staff_provisioning_role_event_mutation()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE check_violation USING MESSAGE = 'Staff role provisioning evidence is append-only',
        CONSTRAINT = 'ck_staff_provisioning_role_event_append_only';
    RETURN NULL;
END;
$$;

CREATE TRIGGER trg_staff_provisioning_role_event_append_only
    BEFORE UPDATE OR DELETE ON access_control.staff_provisioning_role_events
    FOR EACH ROW EXECUTE FUNCTION access_control.reject_staff_provisioning_role_event_mutation();

CREATE TRIGGER trg_staff_provisioning_role_event_no_truncate
    BEFORE TRUNCATE ON access_control.staff_provisioning_role_events
    FOR EACH STATEMENT EXECUTE FUNCTION access_control.reject_staff_provisioning_role_event_mutation();
