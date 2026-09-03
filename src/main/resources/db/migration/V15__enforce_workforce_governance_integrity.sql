CREATE FUNCTION workforce.acquire_governance_tenant_lock(
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
                    'Governance integrity requires a Tenant scope',
                CONSTRAINT =
                    'ck_workforce_governance_tenant_required';
    END IF;

    /*
     * All workforce mutations capable of removing a viable
     * TENANT_GOVERNANCE Staff relationship use this same
     * transaction-scoped lock.
     *
     * Hash collisions may over-serialize independent Tenants but
     * cannot weaken correctness.
     */
    PERFORM pg_advisory_xact_lock(
        hashtextextended(
            'orderhub.workforce.governance:'
                || requested_tenant_id::TEXT,
            0
        )
    );

END;
$$;

CREATE FUNCTION workforce.enforce_staff_governance_integrity()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    target_is_viable_governance BOOLEAN;
    another_viable_governance_exists BOOLEAN;
BEGIN
    /*
     * The trigger is installed only for ACTIVE -> INACTIVE.
     * Serialize before reading current placement/position state.
     */
    PERFORM workforce.acquire_governance_tenant_lock(
        OLD.tenant_id
    );

    SELECT EXISTS (
        SELECT 1
        FROM workforce.staff_placements placement
        JOIN workforce.job_positions position
          ON position.tenant_id =
                placement.tenant_id
         AND position.position_id =
                placement.position_id
        WHERE placement.tenant_id =
                OLD.tenant_id
          AND placement.staff_id =
                OLD.staff_id
          AND position.authority_band =
                'TENANT_GOVERNANCE'
    )
    INTO target_is_viable_governance;

    IF NOT target_is_viable_governance THEN
        RETURN NEW;
    END IF;

    SELECT EXISTS (
        SELECT 1
        FROM workforce.staff_profiles staff
        JOIN workforce.staff_placements placement
          ON placement.tenant_id =
                staff.tenant_id
         AND placement.staff_id =
                staff.staff_id
        JOIN workforce.job_positions position
          ON position.tenant_id =
                placement.tenant_id
         AND position.position_id =
                placement.position_id
        WHERE staff.tenant_id =
                OLD.tenant_id
          AND staff.staff_id <>
                OLD.staff_id
          AND staff.status =
                'ACTIVE'
          AND position.authority_band =
                'TENANT_GOVERNANCE'
    )
    INTO another_viable_governance_exists;

    IF NOT another_viable_governance_exists THEN

        RAISE check_violation
            USING
                MESSAGE =
                    'Tenant must retain at least one ACTIVE Staff in TENANT_GOVERNANCE',
                CONSTRAINT =
                    'ck_workforce_last_governance_staff';
    END IF;

    RETURN NEW;

END;
$$;

CREATE FUNCTION workforce.enforce_placement_governance_integrity()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    old_is_viable_governance BOOLEAN;
    new_is_viable_governance BOOLEAN;
    another_viable_governance_exists BOOLEAN;
BEGIN
    /*
     * DELETE and organizational-position changes may remove one
     * Staff relationship from the viable governance population.
     *
     * Acquire the Tenant lock before resolving position authority
     * so a concurrent position downgrade is visible afterwards.
     */
    PERFORM workforce.acquire_governance_tenant_lock(
        OLD.tenant_id
    );

    SELECT EXISTS (
        SELECT 1
        FROM workforce.staff_profiles staff
        JOIN workforce.job_positions position
          ON position.tenant_id =
                OLD.tenant_id
         AND position.position_id =
                OLD.position_id
        WHERE staff.tenant_id =
                OLD.tenant_id
          AND staff.staff_id =
                OLD.staff_id
          AND staff.status =
                'ACTIVE'
          AND position.authority_band =
                'TENANT_GOVERNANCE'
    )
    INTO old_is_viable_governance;

    IF NOT old_is_viable_governance THEN

        IF TG_OP = 'DELETE' THEN
            RETURN OLD;
        END IF;

        RETURN NEW;
    END IF;

    IF TG_OP = 'UPDATE' THEN

        SELECT EXISTS (
            SELECT 1
            FROM workforce.staff_profiles staff
            JOIN workforce.job_positions position
              ON position.tenant_id =
                    NEW.tenant_id
             AND position.position_id =
                    NEW.position_id
            WHERE NEW.tenant_id =
                    OLD.tenant_id
              AND staff.tenant_id =
                    NEW.tenant_id
              AND staff.staff_id =
                    NEW.staff_id
              AND staff.status =
                    'ACTIVE'
              AND position.authority_band =
                    'TENANT_GOVERNANCE'
        )
        INTO new_is_viable_governance;

        IF new_is_viable_governance THEN
            RETURN NEW;
        END IF;
    END IF;

    SELECT EXISTS (
        SELECT 1
        FROM workforce.staff_profiles staff
        JOIN workforce.staff_placements placement
          ON placement.tenant_id =
                staff.tenant_id
         AND placement.staff_id =
                staff.staff_id
        JOIN workforce.job_positions position
          ON position.tenant_id =
                placement.tenant_id
         AND position.position_id =
                placement.position_id
        WHERE staff.tenant_id =
                OLD.tenant_id
          AND staff.staff_id <>
                OLD.staff_id
          AND staff.status =
                'ACTIVE'
          AND position.authority_band =
                'TENANT_GOVERNANCE'
    )
    INTO another_viable_governance_exists;

    IF NOT another_viable_governance_exists THEN

        RAISE check_violation
            USING
                MESSAGE =
                    'Tenant must retain at least one ACTIVE Staff in TENANT_GOVERNANCE',
                CONSTRAINT =
                    'ck_workforce_last_governance_staff';
    END IF;

    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;

    RETURN NEW;

END;
$$;

CREATE FUNCTION workforce.enforce_position_governance_integrity()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    affected_viable_governance_exists BOOLEAN;
    another_viable_governance_exists BOOLEAN;
BEGIN
    /*
     * Only a transition away from TENANT_GOVERNANCE can contract
     * the viable-governance population.
     */
    IF OLD.authority_band IS DISTINCT FROM 'TENANT_GOVERNANCE'
        OR NEW.authority_band = 'TENANT_GOVERNANCE' THEN

        RETURN NEW;
    END IF;

    PERFORM workforce.acquire_governance_tenant_lock(
        OLD.tenant_id
    );

    SELECT EXISTS (
        SELECT 1
        FROM workforce.staff_profiles staff
        JOIN workforce.staff_placements placement
          ON placement.tenant_id =
                staff.tenant_id
         AND placement.staff_id =
                staff.staff_id
        WHERE staff.tenant_id =
                OLD.tenant_id
          AND staff.status =
                'ACTIVE'
          AND placement.position_id =
                OLD.position_id
    )
    INTO affected_viable_governance_exists;

    IF NOT affected_viable_governance_exists THEN
        RETURN NEW;
    END IF;

    /*
     * OLD still has TENANT_GOVERNANCE inside this BEFORE trigger,
     * so exclude the entire position being downgraded.
     */
    SELECT EXISTS (
        SELECT 1
        FROM workforce.staff_profiles staff
        JOIN workforce.staff_placements placement
          ON placement.tenant_id =
                staff.tenant_id
         AND placement.staff_id =
                staff.staff_id
        JOIN workforce.job_positions position
          ON position.tenant_id =
                placement.tenant_id
         AND position.position_id =
                placement.position_id
        WHERE staff.tenant_id =
                OLD.tenant_id
          AND staff.status =
                'ACTIVE'
          AND position.authority_band =
                'TENANT_GOVERNANCE'
          AND position.position_id <>
                OLD.position_id
    )
    INTO another_viable_governance_exists;

    IF NOT another_viable_governance_exists THEN

        RAISE check_violation
            USING
                MESSAGE =
                    'Tenant must retain at least one ACTIVE Staff in TENANT_GOVERNANCE',
                CONSTRAINT =
                    'ck_workforce_last_governance_staff';
    END IF;

    RETURN NEW;

END;
$$;

CREATE TRIGGER trg_workforce_staff_governance_integrity
    BEFORE UPDATE OF status
    ON workforce.staff_profiles
    FOR EACH ROW
    WHEN (
        OLD.status = 'ACTIVE'
        AND NEW.status = 'INACTIVE'
    )
    EXECUTE FUNCTION workforce.enforce_staff_governance_integrity();

CREATE TRIGGER trg_workforce_placement_governance_integrity
    BEFORE DELETE OR UPDATE OF
        tenant_id,
        staff_id,
        position_id
    ON workforce.staff_placements
    FOR EACH ROW
    EXECUTE FUNCTION workforce.enforce_placement_governance_integrity();

CREATE TRIGGER trg_workforce_position_governance_integrity
    BEFORE UPDATE OF authority_band
    ON workforce.job_positions
    FOR EACH ROW
    WHEN (
        OLD.authority_band IS DISTINCT FROM
            NEW.authority_band
    )
    EXECUTE FUNCTION workforce.enforce_position_governance_integrity();
