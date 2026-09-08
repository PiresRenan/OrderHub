ALTER TABLE users.tenant_memberships
    ADD COLUMN status TEXT;

UPDATE users.tenant_memberships
SET status = 'ACTIVE'
WHERE status IS NULL;

ALTER TABLE users.tenant_memberships
    ALTER COLUMN status SET NOT NULL;

ALTER TABLE users.tenant_memberships
    ADD CONSTRAINT ck_tenant_memberships_status
        CHECK (status IN (
            'ACTIVE',
            'SUSPENDED',
            'TERMINATED'
        ));
