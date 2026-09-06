package io.github.piresrenan.orderhub.analytics.adapter.out.persistence.postgresql;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class PostgreSqlWorkforceAuthorityChangeFactConstraintsTest {

    private static final DockerImageName POSTGRES_IMAGE =
            DockerImageName.parse(
                    "postgres:18.6-trixie@sha256:"
                            + "4ef4dbc939d61acea57712655ddb4b4ab27419c913f94cca0cd57cb3ea3c2280")
                    .asCompatibleSubstituteFor("postgres");

    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(POSTGRES_IMAGE)
                    .withDatabaseName("orderhub_test")
                    .withUsername("orderhub_test")
                    .withPassword("synthetic-test-password");

    private static final String CHECK_VIOLATION = "23514";

    private static final String UNIQUE_VIOLATION = "23505";

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
            """;

    private static final Instant OCCURRED_AT =
            Instant.parse("2026-01-01T00:00:00Z");

    private static JdbcTemplate jdbcTemplate;

    @BeforeAll
    static void migrateAcceptedSchemaChain() {

        var dataSource =
                new DriverManagerDataSource(
                        POSTGRES.getJdbcUrl(),
                        POSTGRES.getUsername(),
                        POSTGRES.getPassword());

        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();

        jdbcTemplate =
                new JdbcTemplate(
                        dataSource);
    }

    @BeforeEach
    void resetFactStorage() {

        jdbcTemplate.update(
                "DELETE FROM analytics.workforce_authority_change_facts");
    }

    private int insertFact(
            String factType,
            int schemaVersion,
            String action,
            String outcome,
            String reasonCode) {

        return jdbcTemplate.update(
                INSERT_FACT,
                UUID.randomUUID(),
                UUID.randomUUID(),
                factType,
                schemaVersion,
                UUID.randomUUID(),
                UUID.randomUUID(),
                action,
                outcome,
                reasonCode,
                Timestamp.from(
                        OCCURRED_AT));
    }

    private static String sqlStateOf(
            Throwable failure) {

        for (var cause = failure; cause != null; cause = cause.getCause()) {

            if (cause instanceof SQLException sqlFailure) {
                return sqlFailure.getSQLState();
            }
        }

        return null;
    }

    /**
     * Proves one malformed value is refused by a PostgreSQL CHECK violation
     * specifically, rather than by any exception at all.
     *
     * <p>
     * Every attempt uses fresh Tenant, source-event and subject identifiers, so
     * a primary-key collision can never masquerade as the intended rejection.
     * The observed SQLSTATE is asserted exactly, which also excludes
     * {@code 23505 unique_violation}.
     * </p>
     */
    private void assertRejectedByCheckViolation(
            String label,
            Runnable insert) {

        var accepted = false;
        String observedSqlState = null;

        try {
            insert.run();
            accepted = true;

        } catch (RuntimeException failure) {
            observedSqlState =
                    sqlStateOf(
                            failure);
        }

        assertThat(accepted)
                .as("%s must be refused by PostgreSQL, not stored", label)
                .isFalse();

        assertThat(observedSqlState)
                .as("%s must be refused by a CHECK violation (%s), never by"
                        + " uniqueness (%s) or another failure",
                        label,
                        CHECK_VIOLATION,
                        UNIQUE_VIOLATION)
                .isEqualTo(
                        CHECK_VIOLATION);
    }

    @Test
    void rejectsValuesOutsideTheBoundedAnalyticalFactContract() {
        // Why: the bounded analytical fact contract is only durable if the
        // database refuses values the Java contract would reject; otherwise a
        // future writer bypassing the domain type could persist an
        // uninterpretable or unversioned analytical row.
        // Covers: acceptance of a valid row, then rejection of an unbounded
        // fact type, a non-positive schema version, an unbounded action, an
        // unbounded outcome and a malformed reason code.
        // Prevents: arbitrary vocabulary, unversioned facts and free-form
        // reason text entering analytical storage through raw SQL.
        //
        // The valid insertion is asserted first so that PostgreSQL, Flyway and
        // the DML path are proven functional before any absence of rejection is
        // interpreted as the missing invariant.

        org.junit.jupiter.api.Assertions.assertAll(
                () -> assertThat(
                        insertFact(
                                "WORKFORCE_AUTHORITY_CHANGE",
                                1,
                                "POSITION_AUTHORITY_CHANGED",
                                "APPLIED",
                                null))
                        .as("A valid analytical fact row must be storable")
                        .isEqualTo(1),

                () -> assertThat(
                        insertFact(
                                "WORKFORCE_AUTHORITY_CHANGE",
                                1,
                                "PRIVILEGED_MUTATION",
                                "DENIED",
                                "PRIVILEGED_POLICY_DENIED"))
                        .as("A bounded reason code must not be over-rejected")
                        .isEqualTo(1),

                () -> assertRejectedByCheckViolation(
                        "An unbounded fact type",
                        () -> insertFact(
                                "UNBOUNDED_FACT",
                                1,
                                "POSITION_CHANGED",
                                "APPLIED",
                                null)),

                () -> assertRejectedByCheckViolation(
                        "A zero schema version",
                        () -> insertFact(
                                "WORKFORCE_AUTHORITY_CHANGE",
                                0,
                                "POSITION_CHANGED",
                                "APPLIED",
                                null)),

                () -> assertRejectedByCheckViolation(
                        "A negative schema version",
                        () -> insertFact(
                                "WORKFORCE_AUTHORITY_CHANGE",
                                -1,
                                "POSITION_CHANGED",
                                "APPLIED",
                                null)),

                () -> assertRejectedByCheckViolation(
                        "An unbounded action",
                        () -> insertFact(
                                "WORKFORCE_AUTHORITY_CHANGE",
                                1,
                                "SALARY_CHANGED",
                                "APPLIED",
                                null)),

                () -> assertRejectedByCheckViolation(
                        "An unbounded outcome",
                        () -> insertFact(
                                "WORKFORCE_AUTHORITY_CHANGE",
                                1,
                                "POSITION_CHANGED",
                                "PARTIALLY_APPLIED",
                                null)),

                () -> assertRejectedByCheckViolation(
                        "A malformed reason code",
                        () -> insertFact(
                                "WORKFORCE_AUTHORITY_CHANGE",
                                1,
                                "POSITION_CHANGED",
                                "APPLIED",
                                "customer complained loudly")));
    }
}
