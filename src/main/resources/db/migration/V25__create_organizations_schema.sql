CREATE SCHEMA organizations;

CREATE TABLE organizations.organizations (
    id UUID NOT NULL,
    name TEXT NOT NULL,
    status TEXT NOT NULL,

    CONSTRAINT pk_organizations
        PRIMARY KEY (id),

    CONSTRAINT ck_organizations_name_not_blank
        CHECK (name ~ '[^[:space:]]'),

    CONSTRAINT ck_organizations_name_normalized
        CHECK (
            name !~ '^[[:space:]]'
            AND name !~ '[[:space:]]$'
        ),

    CONSTRAINT ck_organizations_name_length
        CHECK (char_length(name) <= 120),

    CONSTRAINT ck_organizations_status
        CHECK (
            status IN (
                'ACTIVE',
                'SUSPENDED'
            )
        )
);
