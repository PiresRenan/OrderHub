ALTER TABLE organizations.administrative_audit_events
    ADD CONSTRAINT ck_organization_administrative_audit_lifecycle_destination
        CHECK (
            action_type NOT IN ('SUSPEND_ORGANIZATION', 'RECOVER_ORGANIZATION')
            OR (action_type = 'SUSPEND_ORGANIZATION' AND after_organization_status = 'SUSPENDED')
            OR (action_type = 'RECOVER_ORGANIZATION' AND after_organization_status = 'ACTIVE')
        );

ALTER TABLE tenants.administrative_audit_events
    ADD CONSTRAINT ck_tenant_administrative_audit_lifecycle_destination
        CHECK (
            action_type NOT IN ('SUSPEND_TENANT', 'RECOVER_TENANT')
            OR (action_type = 'SUSPEND_TENANT' AND after_status = 'SUSPENDED')
            OR (action_type = 'RECOVER_TENANT' AND after_status = 'ACTIVE')
        );
