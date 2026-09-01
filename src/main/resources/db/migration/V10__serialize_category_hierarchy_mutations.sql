CREATE TABLE catalog.category_hierarchy_guards (
    tenant_id UUID NOT NULL,

    CONSTRAINT pk_catalog_category_hierarchy_guards
        PRIMARY KEY (tenant_id)
);
