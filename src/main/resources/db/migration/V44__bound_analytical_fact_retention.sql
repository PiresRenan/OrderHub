-- OH-020 analytical-retention index. V44 is the first free Flyway version
-- after the final integrated OH-019 migration history through V43.
CREATE INDEX ix_analytics_workforce_fact_retention
    ON analytics.workforce_authority_change_facts (
        fact_type,
        occurred_at,
        tenant_id,
        source_event_id
    );
