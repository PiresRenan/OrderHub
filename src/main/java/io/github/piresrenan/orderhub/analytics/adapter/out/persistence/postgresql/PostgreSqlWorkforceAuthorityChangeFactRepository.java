package io.github.piresrenan.orderhub.analytics.adapter.out.persistence.postgresql;

import java.sql.Timestamp;
import java.util.List;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import io.github.piresrenan.orderhub.analytics.application.port.out.WorkforceAuthorityChangeFactPersistenceException;
import io.github.piresrenan.orderhub.analytics.application.port.out.WorkforceAuthorityChangeFactRepository;
import io.github.piresrenan.orderhub.analytics.domain.model.WorkforceAuthorityChangeFact;

/**
 * PostgreSQL append adapter for analytical workforce authority-change facts.
 *
 * <p>
 * This adapter deliberately owns no independent transaction boundary.
 * JdbcTemplate therefore participates in the transaction established by the
 * caller, if any.
 * </p>
 *
 * <p>
 * Duplicate identity is arbitrated by PostgreSQL in the insertion statement
 * itself rather than by application coordination or JVM locking. The conflict
 * target is the relation's fact identity, and the conflict action is
 * deliberately {@code DO NOTHING}: an already-persisted analytical fact is
 * historical evidence and is never overwritten, not even by self-assignment.
 * </p>
 *
 * <p>
 * The adapter provides durable analytical fact storage only. It is not a
 * workforce ingestion mechanism and selects no transport, occurrence-time
 * source, commit-order cursor, retry, recovery or replay behaviour.
 * </p>
 */
public final class PostgreSqlWorkforceAuthorityChangeFactRepository
        implements WorkforceAuthorityChangeFactRepository {

    /**
     * Persists the fact unless its identity already exists.
     *
     * <p>
     * {@code RETURNING 1} yields one row only when this statement actually
     * inserted, so this invocation learns whether it stored the fact without
     * relying on a uniqueness exception as normal control flow.
     * </p>
     */
    private static final String INSERT_FACT =
            """
            INSERT INTO analytics.workforce_authority_change_facts (
                tenant_id,
                source_event_id,
                fact_type,
                schema_version,
                actor_subject_key,
                affected_subject_key,
                action,
                outcome,
                reason_code,
                occurred_at
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (
                tenant_id,
                source_event_id,
                fact_type
            )
            DO NOTHING
            RETURNING 1
            """;

    /**
     * Compares the already-persisted fact against the candidate.
     *
     * <p>
     * The comparison is evaluated by PostgreSQL rather than in Java. The
     * candidate's occurrence time is sent back as a bound parameter and
     * compared against the stored column, so both sides are read in
     * PostgreSQL's own temporal representation. An exact replay carrying a
     * higher-precision {@link java.time.Instant} therefore cannot become a
     * false conflict merely because {@code TIMESTAMPTZ} stores less precision
     * than the domain value can express.
     * </p>
     *
     * <p>
     * The nullable reason code is compared with {@code IS NOT DISTINCT FROM},
     * because {@code NULL = NULL} is not true in SQL and two facts that both
     * carry no reason are the same fact.
     * </p>
     */
    private static final String COMPARE_PERSISTED_FACT =
            """
            SELECT
                schema_version = ?
                AND actor_subject_key = ?
                AND affected_subject_key = ?
                AND action = ?
                AND outcome = ?
                AND reason_code IS NOT DISTINCT FROM ?
                AND occurred_at = ?
                    AS semantic_match
            FROM analytics.workforce_authority_change_facts
            WHERE tenant_id = ?
              AND source_event_id = ?
              AND fact_type = ?
            """;

    private final JdbcTemplate jdbcTemplate;

    public PostgreSqlWorkforceAuthorityChangeFactRepository(
            JdbcTemplate jdbcTemplate) {

        if (jdbcTemplate == null) {
            throw new IllegalArgumentException(
                    "JdbcTemplate is required");
        }

        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void append(
            WorkforceAuthorityChangeFact fact) {

        if (fact == null) {
            throw new IllegalArgumentException(
                    "Workforce authority change fact is required");
        }

        final boolean inserted;

        try {
            inserted =
                    !jdbcTemplate.query(
                                    INSERT_FACT,
                                    (resultSet, rowNumber) ->
                                            resultSet.getInt(
                                                    1),
                                    fact.tenantId(),
                                    fact.sourceEventId(),
                                    fact.factType().name(),
                                    fact.schemaVersion(),
                                    fact.actorSubject().value(),
                                    fact.affectedSubject().value(),
                                    fact.action().name(),
                                    fact.outcome().name(),
                                    fact.reasonCode(),
                                    Timestamp.from(
                                            fact.occurredAt()))
                            .isEmpty();

        } catch (DataAccessException exception) {
            throw new WorkforceAuthorityChangeFactPersistenceException(
                    "Failed to append analytical workforce authority-change"
                            + " fact",
                    exception);
        }

        if (inserted) {
            return;
        }

        requireExactReplay(
                fact);
    }

    /**
     * Accepts an exact replay of an already-persisted identity and rejects a
     * divergent candidate.
     *
     * <p>
     * The semantic conflicts raised here are deliberately thrown outside the
     * data-access catch, so an application-owned conflict is never re-wrapped
     * as a technical failure and the two remain distinguishable internally
     * even though both reach the caller as the same public type.
     * </p>
     */
    private void requireExactReplay(
            WorkforceAuthorityChangeFact fact) {

        final List<Boolean> semanticMatches;

        try {
            semanticMatches =
                    jdbcTemplate.query(
                            COMPARE_PERSISTED_FACT,
                            (resultSet, rowNumber) ->
                                    resultSet.getObject(
                                            "semantic_match",
                                            Boolean.class),
                            fact.schemaVersion(),
                            fact.actorSubject().value(),
                            fact.affectedSubject().value(),
                            fact.action().name(),
                            fact.outcome().name(),
                            fact.reasonCode(),
                            Timestamp.from(
                                    fact.occurredAt()),
                            fact.tenantId(),
                            fact.sourceEventId(),
                            fact.factType().name());

        } catch (DataAccessException exception) {
            throw new WorkforceAuthorityChangeFactPersistenceException(
                    "Failed to resolve analytical fact identity conflict",
                    exception);
        }

        // The accepted primary key makes exactly one row the only coherent
        // outcome here. Anything else is unexplained persistence state, so the
        // adapter fails closed rather than guessing which row is authoritative.
        if (semanticMatches.size() != 1) {
            throw new WorkforceAuthorityChangeFactPersistenceException(
                    "Analytical fact conflict did not resolve to exactly one"
                            + " durable row");
        }

        if (!Boolean.TRUE.equals(
                semanticMatches.get(0))) {

            throw new WorkforceAuthorityChangeFactPersistenceException(
                    "Analytical fact identity is already persisted with"
                            + " conflicting content");
        }
    }
}
