-- ---------------------------------------------------------------------------
-- Privacy-safe analytical workforce authority-change facts
--
-- The relation stores the bounded analytical fact contract and nothing else.
-- Subjects are analytics-owned opaque keys rather than operational Staff or
-- User identifiers, and there is no payload, correlation or organizational
-- state column.
--
-- There are deliberately no foreign keys. A reference into
-- analytics.subject_pseudonyms would tie a fact row's lifetime to the
-- identifying mapping, whose unlink and deletion semantics are not yet
-- decided; a reference into an operational schema would let operational
-- persistence dictate analytical state.
--
-- Occurrence time is supplied by the analytical fact contract. This migration
-- introduces no default, so the database never substitutes insertion time for
-- the operational occurrence time that retention is anchored to.
--
-- Tenant scope, source-event provenance and analytical projection identity
-- form the primary key. schema_version describes how one projection is
-- interpreted rather than identifying a second projection, so it is persisted
-- but is deliberately not part of identity.
-- ---------------------------------------------------------------------------

CREATE TABLE analytics.workforce_authority_change_facts (
    tenant_id UUID NOT NULL,
    source_event_id UUID NOT NULL,
    fact_type TEXT NOT NULL,
    schema_version INTEGER NOT NULL,
    actor_subject_key UUID NOT NULL,
    affected_subject_key UUID NOT NULL,
    action TEXT NOT NULL,
    outcome TEXT NOT NULL,
    reason_code TEXT,
    occurred_at TIMESTAMPTZ NOT NULL,

    CONSTRAINT pk_analytics_workforce_authority_change_facts
        PRIMARY KEY (
            tenant_id,
            source_event_id,
            fact_type
        ),

    CONSTRAINT ck_analytics_workforce_fact_type
        CHECK (
            fact_type IN (
                'WORKFORCE_AUTHORITY_CHANGE'
            )
        ),

    CONSTRAINT ck_analytics_workforce_schema_version
        CHECK (
            schema_version > 0
        ),

    CONSTRAINT ck_analytics_workforce_action
        CHECK (
            action IN (
                'POSITION_CHANGED',
                'POSITION_AUTHORITY_CHANGED',
                'PRIVILEGED_MUTATION'
            )
        ),

    CONSTRAINT ck_analytics_workforce_outcome
        CHECK (
            outcome IN (
                'APPLIED',
                'DENIED'
            )
        ),

    CONSTRAINT ck_analytics_workforce_reason_code
        CHECK (
            reason_code IS NULL
            OR reason_code ~ '^[A-Z][A-Z0-9_]{2,63}$'
        )
);
