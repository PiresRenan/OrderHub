-- ---------------------------------------------------------------------------
-- Spring Modulith JDBC event publication registry
--
-- The relation is the durable publication log of Spring Modulith's JDBC Event
-- Publication Registry. Its structure follows the Spring Modulith 2.1.1
-- PostgreSQL schema for that registry, so the framework finds exactly the
-- shape it expects.
--
-- Flyway owns the relation's lifecycle. Framework schema initialization is
-- therefore not the source of this structure, and the DDL below is deliberately
-- fail-closed rather than tolerant of an existing object: a pre-existing
-- conflicting relation or index must fail the migration instead of being
-- silently accepted.
--
-- The registry is integration infrastructure rather than application state, so
-- it lives in the default schema and is deliberately not owned by analytics,
-- workforce or any other application module schema. The explicit qualification
-- keeps that ownership independent of a mutable search_path.
--
-- There are deliberately no foreign keys. Publication rows describe delivery
-- lifecycle, not domain relationships, and an edge into an operational
-- aggregate would let business data dictate integration state.
-- ---------------------------------------------------------------------------

CREATE TABLE public.event_publication (
    id UUID NOT NULL,
    listener_id TEXT NOT NULL,
    event_type TEXT NOT NULL,
    serialized_event TEXT NOT NULL,
    publication_date TIMESTAMP WITH TIME ZONE NOT NULL,
    completion_date TIMESTAMP WITH TIME ZONE,
    status TEXT,
    completion_attempts INT,
    last_resubmission_date TIMESTAMP WITH TIME ZONE,

    PRIMARY KEY (id)
);

CREATE INDEX event_publication_serialized_event_hash_idx
    ON public.event_publication
    USING hash (serialized_event);

CREATE INDEX event_publication_by_completion_date_idx
    ON public.event_publication (completion_date);
