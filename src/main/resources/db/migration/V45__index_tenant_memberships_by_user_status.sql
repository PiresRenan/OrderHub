-- OH-023 / ADR-0021: bounded self-scoped Tenant discovery scans one User's
-- ACTIVE memberships in tenant_id order. The only prior index,
-- UNIQUE (tenant_id, user_id), does not lead with user_id, so each scan was a
-- full-table sequential scan. The non-partial column order keeps index-only
-- access in both custom and generic (server-prepared) plans, where the
-- lifecycle status is a bound parameter.
CREATE INDEX ix_tenant_memberships_user_status_tenant
    ON users.tenant_memberships (user_id, status, tenant_id);
