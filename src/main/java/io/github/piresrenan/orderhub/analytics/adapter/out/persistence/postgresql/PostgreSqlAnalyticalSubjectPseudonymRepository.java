package io.github.piresrenan.orderhub.analytics.adapter.out.persistence.postgresql;

import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;

import io.github.piresrenan.orderhub.analytics.application.port.out.AnalyticalSubjectPseudonymRepository;
import io.github.piresrenan.orderhub.analytics.domain.model.AnalyticalSubjectKey;

/**
 * PostgreSQL resolution adapter for pseudonymous analytical subject identity.
 *
 * <p>
 * The adapter owns no transaction boundary. JdbcTemplate therefore participates
 * in the transaction established by the caller, if any.
 * </p>
 *
 * <p>
 * The analytical key is generated here rather than by PostgreSQL, so the
 * migration introduces no default and no generation function.
 * </p>
 *
 * <p>
 * Concurrent first resolution of one tuple is arbitrated by PostgreSQL in the
 * persistence statement itself, not by application coordination. The conflict
 * target is the mapping's natural key, and the already-persisted analytical
 * identity always wins, so competing callers converge on the identity that
 * actually remains stored.
 * </p>
 */
public final class PostgreSqlAnalyticalSubjectPseudonymRepository
        implements AnalyticalSubjectPseudonymRepository {

    private static final String LOOKUP_MAPPING =
            """
            SELECT analytical_subject_key
            FROM analytics.subject_pseudonyms
            WHERE tenant_id = ?
              AND operational_subject_id = ?
            """;

    /**
     * Establishes the mapping, or yields the mapping a competing caller
     * established first.
     *
     * <p>
     * The conflict target is the mapping's natural key. On conflict the
     * assignment deliberately preserves {@code existing.analytical_subject_key}
     * rather than {@code EXCLUDED.analytical_subject_key}, so a losing
     * candidate can never replace a durable analytical identity. RETURNING
     * therefore yields the identity that remains persisted in both branches.
     * </p>
     */
    private static final String INSERT_OR_RESOLVE_MAPPING =
            """
            INSERT INTO analytics.subject_pseudonyms AS existing (
                tenant_id,
                operational_subject_id,
                analytical_subject_key
            )
            VALUES (?, ?, ?)
            ON CONFLICT (tenant_id, operational_subject_id)
            DO UPDATE
            SET analytical_subject_key = existing.analytical_subject_key
            RETURNING analytical_subject_key
            """;

    private final JdbcTemplate jdbcTemplate;

    public PostgreSqlAnalyticalSubjectPseudonymRepository(
            JdbcTemplate jdbcTemplate) {

        if (jdbcTemplate == null) {
            throw new IllegalArgumentException(
                    "JdbcTemplate is required");
        }

        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public AnalyticalSubjectKey resolveOrCreate(
            UUID tenantId,
            UUID operationalSubjectId) {

        if (tenantId == null) {
            throw new IllegalArgumentException(
                    "Tenant ID is required");
        }

        if (operationalSubjectId == null) {
            throw new IllegalArgumentException(
                    "Operational subject ID is required");
        }

        var existing =
                jdbcTemplate.queryForList(
                        LOOKUP_MAPPING,
                        UUID.class,
                        tenantId,
                        operationalSubjectId);

        if (!existing.isEmpty()) {

            return new AnalyticalSubjectKey(
                    existing.get(0));
        }

        var candidateKey =
                UUID.randomUUID();

        // The candidate is only a proposal. PostgreSQL returns whichever
        // identity actually remains persisted, which is not the candidate when
        // a competing caller established the mapping first.
        var establishedKey =
                jdbcTemplate.queryForObject(
                        INSERT_OR_RESOLVE_MAPPING,
                        UUID.class,
                        tenantId,
                        operationalSubjectId,
                        candidateKey);

        return new AnalyticalSubjectKey(
                establishedKey);
    }
}
