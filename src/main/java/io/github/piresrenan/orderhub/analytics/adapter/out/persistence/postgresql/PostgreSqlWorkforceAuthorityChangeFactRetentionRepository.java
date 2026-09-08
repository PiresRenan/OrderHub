package io.github.piresrenan.orderhub.analytics.adapter.out.persistence.postgresql;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import io.github.piresrenan.orderhub.analytics.application.port.out.WorkforceAuthorityChangeFactPersistenceException;
import io.github.piresrenan.orderhub.analytics.application.port.out.WorkforceAuthorityChangeFactRetentionRepository;
import io.github.piresrenan.orderhub.analytics.domain.model.AnalyticalFactType;

/**
 * PostgreSQL retention adapter for analytical workforce authority-change facts.
 *
 * <p>
 * This adapter deliberately owns no independent transaction boundary.
 * JdbcTemplate therefore participates in the transaction established by the
 * caller, if any.
 * </p>
 *
 * <p>
 * Removal is one predicate statement. PostgreSQL evaluates the predicate
 * itself, so two purges of the same scope converge without application
 * coordination or JVM locking: a row another purge already removed is simply
 * not matched again.
 * </p>
 *
 * <p>
 * The statement targets analytics-owned fact storage only. It never references
 * the identifying subject mapping or any operational schema, so retention
 * cannot remove the more sensitive mapping or immutable workforce audit
 * evidence.
 * </p>
 */
public final class PostgreSqlWorkforceAuthorityChangeFactRetentionRepository
        implements WorkforceAuthorityChangeFactRetentionRepository {

    /**
     * Removes the expired facts of one Tenant.
     *
     * <p>
     * The fact-type discriminator is bound even though this adapter serves one
     * fact-specific relation, because the cutoff was derived from the retention
     * policy of that fact type. Keeping the discriminator in the predicate
     * makes the persistence statement express the same policy scope the
     * application applied.
     * </p>
     *
     * <p>
     * The occurrence-time comparison is inclusive, so a fact whose lifetime
     * ends exactly at the cutoff is removed rather than retained for one more
     * cycle.
     * </p>
     */
    private static final String DELETE_EXPIRED_FACTS =
            """
            WITH candidates AS (
                SELECT tenant_id, source_event_id, fact_type
                FROM analytics.workforce_authority_change_facts
                WHERE tenant_id = ?
                  AND fact_type = ?
                  AND occurred_at <= ?
                ORDER BY occurred_at, tenant_id, source_event_id
                LIMIT 1000
                FOR UPDATE
            )
            DELETE FROM analytics.workforce_authority_change_facts AS facts
            USING candidates
            WHERE facts.tenant_id = candidates.tenant_id
              AND facts.source_event_id = candidates.source_event_id
              AND facts.fact_type = candidates.fact_type
            """;

    private static final String DELETE_EXPIRED_FACT_BATCH =
            """
            WITH candidates AS (
                SELECT tenant_id, source_event_id, fact_type
                FROM analytics.workforce_authority_change_facts
                WHERE fact_type = ?
                  AND occurred_at <= ?
                ORDER BY occurred_at, tenant_id, source_event_id
                LIMIT ?
                FOR UPDATE SKIP LOCKED
            )
            DELETE FROM analytics.workforce_authority_change_facts AS facts
            USING candidates
            WHERE facts.tenant_id = candidates.tenant_id
              AND facts.source_event_id = candidates.source_event_id
              AND facts.fact_type = candidates.fact_type
            """;

    private final JdbcTemplate jdbcTemplate;

    public PostgreSqlWorkforceAuthorityChangeFactRetentionRepository(
            JdbcTemplate jdbcTemplate) {

        if (jdbcTemplate == null) {
            throw new IllegalArgumentException(
                    "JdbcTemplate is required");
        }

        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public int deleteExpired(
            UUID tenantId,
            Instant occurredAtOrBefore) {

        if (tenantId == null) {
            throw new IllegalArgumentException(
                    "Tenant ID is required");
        }

        if (occurredAtOrBefore == null) {
            throw new IllegalArgumentException(
                    "Occurrence time cutoff is required");
        }

        try {
            return jdbcTemplate.update(
                    DELETE_EXPIRED_FACTS,
                    tenantId,
                    AnalyticalFactType.WORKFORCE_AUTHORITY_CHANGE.name(),
                    Timestamp.from(
                            occurredAtOrBefore));

        } catch (DataAccessException exception) {
            throw new WorkforceAuthorityChangeFactPersistenceException(
                    "Failed to purge expired workforce analytical facts",
                    exception);
        }
    }

    @Override
    public int deleteExpired(
            Instant occurredAtOrBefore,
            int batchSize) {

        if (occurredAtOrBefore == null) {
            throw new IllegalArgumentException(
                    "Occurrence time cutoff is required");
        }

        if (batchSize < 1 || batchSize > 1000) {
            throw new IllegalArgumentException(
                    "Batch size must be between 1 and 1000");
        }

        try {
            return jdbcTemplate.update(
                    DELETE_EXPIRED_FACT_BATCH,
                    AnalyticalFactType.WORKFORCE_AUTHORITY_CHANGE.name(),
                    Timestamp.from(occurredAtOrBefore),
                    batchSize);
        } catch (DataAccessException exception) {
            throw new WorkforceAuthorityChangeFactPersistenceException(
                    "Failed to purge expired workforce analytical facts",
                    exception);
        }
    }
}
