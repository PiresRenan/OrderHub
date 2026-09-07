CREATE TABLE organizations.tenant_placements (
    tenant_id UUID NOT NULL,
    organization_id UUID NOT NULL,

    CONSTRAINT pk_organization_tenant_placements
        PRIMARY KEY (tenant_id),

    CONSTRAINT fk_organization_tenant_placements_organization
        FOREIGN KEY (organization_id)
        REFERENCES organizations.organizations (id)
);

CREATE INDEX idx_organization_tenant_placements_organization
    ON organizations.tenant_placements (
        organization_id,
        tenant_id
    );
