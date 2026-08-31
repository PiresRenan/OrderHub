CREATE SCHEMA users;

CREATE TABLE users.users (
    id UUID NOT NULL,

    CONSTRAINT pk_users
        PRIMARY KEY (id)
);

CREATE TABLE users.tenant_memberships (
    user_id UUID NOT NULL,
    tenant_id UUID NOT NULL,

    CONSTRAINT uq_tenant_memberships_tenant_user
        UNIQUE (tenant_id, user_id)
);
