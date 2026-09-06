-- ---------------------------------------------------------------------------
-- Durable pseudonymous analytical subject identity
--
-- This relation durably associates one Tenant-scoped operational subject with
-- one opaque analytical subject key.
--
-- There are deliberately no foreign keys into users, workforce or any other
-- operational schema. The analytical key is supplied by the application;
-- this migration introduces no default or generation function.
-- ---------------------------------------------------------------------------

CREATE SCHEMA analytics;

CREATE TABLE analytics.subject_pseudonyms (
    tenant_id UUID NOT NULL,
    operational_subject_id UUID NOT NULL,
    analytical_subject_key UUID NOT NULL,

    -- Exactly one analytical identity per operational subject inside one
    -- Tenant.
    CONSTRAINT pk_analytics_subject_pseudonyms
        PRIMARY KEY (
            tenant_id,
            operational_subject_id
        ),

    -- One analytical key never represents two mappings. This is what makes
    -- Tenant isolation a structural property rather than a probabilistic one:
    -- two mappings that both persist cannot share an analytical identity.
    CONSTRAINT uq_analytics_subject_pseudonym_key
        UNIQUE (analytical_subject_key)
);
