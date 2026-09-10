-- A caller timestamp captured before UPDATE can become stale while PostgreSQL
-- waits for the intent row. A BEFORE ROW trigger executes after that row is
-- acquired, so the actual transition cannot spend an already expired proof.
-- Keep the original conditional UPDATE RETURNING contract: rejection returns
-- no row, without a pre-read, independent commit or identity side effect.
CREATE FUNCTION workforce.enforce_staff_proof_consumption_deadline()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.consumed_at IS NULL AND NEW.consumed_at IS NOT NULL
       AND clock_timestamp() >= OLD.expires_at THEN
        RETURN NULL;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_workforce_staff_proof_consumption_deadline
BEFORE UPDATE ON workforce.staff_provisioning_intents
FOR EACH ROW EXECUTE FUNCTION workforce.enforce_staff_proof_consumption_deadline();
