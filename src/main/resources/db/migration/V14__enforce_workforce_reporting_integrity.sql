CREATE FUNCTION workforce.acquire_reporting_tenant_lock(
        requested_tenant_id UUID
)
RETURNS VOID
LANGUAGE plpgsql
AS $$
BEGIN
    IF requested_tenant_id IS NULL THEN

        RAISE check_violation
            USING
                MESSAGE =
                    'Reporting integrity requires a Tenant scope',
                CONSTRAINT =
                    'ck_workforce_reporting_tenant_required';
    END IF;

    /*
     * The advisory key is deliberately not persisted.
     *
     * A hash collision can only serialize two otherwise-independent Tenants;
     * it cannot weaken correctness.
     */
    PERFORM pg_advisory_xact_lock(
        hashtextextended(
            'orderhub.workforce.reporting:'
                || requested_tenant_id::TEXT,
            0
        )
    );

END;
$$;

CREATE FUNCTION workforce.enforce_reporting_relationship_integrity()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    supervisor_status TEXT;
    cycle_detected BOOLEAN;
BEGIN
    PERFORM workforce.acquire_reporting_tenant_lock(
        NEW.tenant_id
    );

    /*
     * This SELECT executes after acquisition of the Tenant transaction lock.
     * Under READ COMMITTED it therefore observes a competing transaction that
     * committed before this validation command begins.
     */
    SELECT staff.status
    INTO supervisor_status
    FROM workforce.staff_profiles staff
    WHERE staff.tenant_id = NEW.tenant_id
      AND staff.staff_id = NEW.supervisor_staff_id;

    IF supervisor_status IS DISTINCT FROM 'ACTIVE' THEN

        RAISE check_violation
            USING
                MESSAGE =
                    'Only ACTIVE Staff may remain an active supervisor',
                CONSTRAINT =
                    'ck_workforce_reporting_supervisor_active';
    END IF;

    /*
     * Adding supervisor -> subordinate creates a cycle exactly when an
     * existing path subordinate -> ... -> supervisor already exists.
     *
     * UPDATE excludes the edge being replaced from the recursive graph.
     */
    IF TG_OP = 'UPDATE' THEN

        WITH RECURSIVE reachable(staff_id) AS (

            SELECT relationship.subordinate_staff_id
            FROM workforce.reporting_relationships relationship
            WHERE relationship.tenant_id = NEW.tenant_id
              AND relationship.supervisor_staff_id =
                    NEW.subordinate_staff_id
              AND NOT (
                    relationship.tenant_id = OLD.tenant_id
                    AND relationship.supervisor_staff_id =
                        OLD.supervisor_staff_id
                    AND relationship.subordinate_staff_id =
                        OLD.subordinate_staff_id
              )

            UNION

            SELECT relationship.subordinate_staff_id
            FROM workforce.reporting_relationships relationship
            JOIN reachable
              ON relationship.supervisor_staff_id =
                    reachable.staff_id
            WHERE relationship.tenant_id = NEW.tenant_id
              AND NOT (
                    relationship.tenant_id = OLD.tenant_id
                    AND relationship.supervisor_staff_id =
                        OLD.supervisor_staff_id
                    AND relationship.subordinate_staff_id =
                        OLD.subordinate_staff_id
              )
        )
        SELECT EXISTS (
            SELECT 1
            FROM reachable
            WHERE reachable.staff_id =
                NEW.supervisor_staff_id
        )
        INTO cycle_detected;

    ELSE

        WITH RECURSIVE reachable(staff_id) AS (

            SELECT relationship.subordinate_staff_id
            FROM workforce.reporting_relationships relationship
            WHERE relationship.tenant_id = NEW.tenant_id
              AND relationship.supervisor_staff_id =
                    NEW.subordinate_staff_id

            UNION

            SELECT relationship.subordinate_staff_id
            FROM workforce.reporting_relationships relationship
            JOIN reachable
              ON relationship.supervisor_staff_id =
                    reachable.staff_id
            WHERE relationship.tenant_id = NEW.tenant_id
        )
        SELECT EXISTS (
            SELECT 1
            FROM reachable
            WHERE reachable.staff_id =
                NEW.supervisor_staff_id
        )
        INTO cycle_detected;

    END IF;

    IF cycle_detected THEN

        RAISE check_violation
            USING
                MESSAGE =
                    'Reporting relationship would create a Tenant workforce cycle',
                CONSTRAINT =
                    'ck_workforce_reporting_acyclic';
    END IF;

    RETURN NEW;

END;
$$;

CREATE FUNCTION workforce.enforce_inactive_staff_not_supervisor()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.status IS DISTINCT FROM NEW.status
        AND NEW.status = 'INACTIVE' THEN

        PERFORM workforce.acquire_reporting_tenant_lock(
            NEW.tenant_id
        );

        IF EXISTS (
            SELECT 1
            FROM workforce.reporting_relationships relationship
            WHERE relationship.tenant_id = NEW.tenant_id
              AND relationship.supervisor_staff_id =
                    NEW.staff_id
        ) THEN

            RAISE check_violation
                USING
                    MESSAGE =
                        'Staff with active subordinate relationships cannot become INACTIVE',
                    CONSTRAINT =
                        'ck_workforce_inactive_staff_not_supervisor';
        END IF;
    END IF;

    RETURN NEW;

END;
$$;

CREATE TRIGGER trg_workforce_reporting_integrity
    BEFORE INSERT OR UPDATE OF
        tenant_id,
        supervisor_staff_id,
        subordinate_staff_id
    ON workforce.reporting_relationships
    FOR EACH ROW
    EXECUTE FUNCTION workforce.enforce_reporting_relationship_integrity();

CREATE TRIGGER trg_workforce_staff_inactive_supervisor
    BEFORE UPDATE OF status
    ON workforce.staff_profiles
    FOR EACH ROW
    WHEN (
        OLD.status IS DISTINCT FROM NEW.status
        AND NEW.status = 'INACTIVE'
    )
    EXECUTE FUNCTION workforce.enforce_inactive_staff_not_supervisor();
