CREATE SCHEMA tenants;

CREATE TABLE tenants.tenants (
    id UUID NOT NULL,
    name TEXT NOT NULL,

    CONSTRAINT pk_tenants
        PRIMARY KEY (id),

    CONSTRAINT ck_tenants_name_not_blank
        CHECK (name ~ '[^[:space:]]'),

    CONSTRAINT ck_tenants_name_normalized
        CHECK (
            name !~ '^[[:space:]]'
            AND name !~ '[[:space:]]$'
        ),

    CONSTRAINT ck_tenants_name_length
        CHECK (char_length(name) <= 120)
);