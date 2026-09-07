ALTER TABLE tenants.tenants
    ADD COLUMN status TEXT;

UPDATE tenants.tenants
SET status = 'ACTIVE'
WHERE status IS NULL;

ALTER TABLE tenants.tenants
    ALTER COLUMN status SET NOT NULL;

ALTER TABLE tenants.tenants
    ADD CONSTRAINT ck_tenants_status
        CHECK (
            status IN (
                'ACTIVE',
                'SUSPENDED'
            )
        );
