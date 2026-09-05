package io.github.piresrenan.orderhub.analytics.adapter.out.persistence.postgresql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
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

import io.github.piresrenan.orderhub.analytics.application.port.out.WorkforceAuthorityChangeFactPersistenceException;
import io.github.piresrenan.orderhub.analytics.application.port.out.WorkforceAuthorityChangeFactRepository;
import io.github.piresrenan.orderhub.analytics.domain.model.AnalyticalFactType;
import io.github.piresrenan.orderhub.analytics.domain.model.AnalyticalSubjectKey;
import io.github.piresrenan.orderhub.analytics.domain.model.WorkforceAuthorityChangeAction;
import io.github.piresrenan.orderhub.analytics.domain.model.WorkforceAuthorityChangeFact;
import io.github.piresrenan.orderhub.analytics.domain.model.WorkforceAuthorityChangeOutcome;

@Testcontainers
class PostgreSqlWorkforceAuthorityChangeFactRepositoryTest {

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

    private static final UUID TENANT_ID =
            UUID.fromString("00000000-0000-4000-8000-00000000a001");

    private static final UUID SOURCE_EVENT_ID =
            UUID.fromString("00000000-0000-4000-8000-00000000b001");

    private static final AnalyticalSubjectKey ACTOR_SUBJECT =
            new AnalyticalSubjectKey(
                    UUID.fromString("00000000-0000-4000-8000-00000000c001"));

    private static final AnalyticalSubjectKey AFFECTED_SUBJECT =
            new AnalyticalSubjectKey(
                    UUID.fromString("00000000-0000-4000-8000-00000000d001"));

    private static final WorkforceAuthorityChangeAction BASE_ACTION =
            WorkforceAuthorityChangeAction.POSITION_AUTHORITY_CHANGED;

    private static final WorkforceAuthorityChangeOutcome BASE_OUTCOME =
            WorkforceAuthorityChangeOutcome.APPLIED;

    private static final String BASE_REASON_CODE =
            "AUTHORITY_BAND_RAISED";

    /*
     * PostgreSQL TIMESTAMPTZ stores microseconds. Both occurrence times are
     * therefore expressed at exact microsecond precision, so this test asserts
     * only round-trip behaviour the accepted column can actually represent and
     * never claims nanosecond fidelity.
     */
    private static final Instant BASE_OCCURRED_AT =
            Instant.parse("2026-03-04T09:15:30.123456Z");

    private static final Instant DIVERGENT_OCCURRED_AT =
            Instant.parse("2026-03-04T09:15:31.654321Z");

    /*
     * Deliberately not microsecond-aligned, so PostgreSQL cannot persist this
     * value exactly. It exists to characterise replay across that precision
     * reduction and is never compared against a persisted timestamp.
     */
    private static final Instant SUB_MICROSECOND_OCCURRED_AT =
            Instant.parse("2026-03-04T09:15:30.123456789Z");

    private static JdbcTemplate jdbcTemplate;

    private WorkforceAuthorityChangeFactRepository repository;

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

        repository =
                new PostgreSqlWorkforceAuthorityChangeFactRepository(
                        jdbcTemplate);
    }

    @Test
    void appendsIdempotentlyAndRejectsConflictingContentForTheSameIdentity() {
        // Why: the accepted fact contract names sourceEventId the idempotency
        // key for at-least-once ingestion, so a repository that failed on the
        // second delivery of the same fact would make safe retry impossible,
        // while one that accepted any duplicate identity would silently absorb
        // a producer defect, schema-version drift or a nondeterministic
        // projection.
        // Covers: exact first-append representation of all ten bounded
        // components, idempotent success for an exact semantic duplicate,
        // fail-closed rejection of every divergent non-key component under the
        // same identity, and preservation of the originally persisted row.
        // Prevents: strict duplicate failure, silent duplicate absorption,
        // overwrite/upsert of analytical fact content, and a second row for one
        // logical fact identity.
        //
        // Identity is fixed by the shared Tenant, source-event and fact-type
        // constants, so every divergent candidate below differs only outside
        // the accepted primary key.

        var baseFact = baseFact();

        var expectedRepresentation =
                representationOf(
                        baseFact);

        repository.append(
                baseFact);

        assertThat(rowCountForIdentity())
                .as("The first append must persist exactly one fact row")
                .isEqualTo(1);

        assertThat(persistedFact())
                .as("The persisted row must carry exactly the bounded fact"
                        + " contract, losing and adding nothing")
                .isEqualTo(expectedRepresentation);

        repository.append(
                baseFact);

        assertThat(rowCountForIdentity())
                .as("An exact semantic duplicate must be an idempotent no-op,"
                        + " never a second row")
                .isEqualTo(1);

        assertThat(persistedFact())
                .as("An idempotent duplicate append must leave the persisted"
                        + " fact untouched")
                .isEqualTo(expectedRepresentation);

        for (var candidate : divergentCandidates(baseFact)) {

            assertThatThrownBy(() ->
                    repository.append(
                            candidate.fact()))
                    .as("%s under the same identity must fail closed through"
                            + " the analytics persistence boundary",
                            candidate.label())
                    .isInstanceOf(
                            WorkforceAuthorityChangeFactPersistenceException.class);

            assertThat(rowCountForIdentity())
                    .as("%s must not create a second row for one identity",
                            candidate.label())
                    .isEqualTo(1);

            assertThat(persistedFact())
                    .as("%s must never overwrite the already persisted fact",
                            candidate.label())
                    .isEqualTo(expectedRepresentation);
        }

        assertThat(totalRowCount())
                .as("The whole conflict sequence must leave exactly the one"
                        + " originally appended fact in storage")
                .isEqualTo(1);

        assertThat(persistedFact())
                .as("The originally appended fact must survive every rejected"
                        + " conflicting append unchanged")
                .isEqualTo(expectedRepresentation);
    }

    @Test
    void replaysAFactWithoutAReasonCodeIdempotently() {
        // Why: reason_code is the only nullable persisted component, and SQL
        // equality never holds between two NULLs, so a conflict comparison
        // written with plain equality would classify the replay of a
        // reasonless fact as a content conflict and break retry for exactly
        // the facts that carry no reason.
        // Covers: exact replay of a valid fact whose reason code is absent,
        // and the null reason code actually reaching storage.
        // Prevents: null-unsafe comparison of the nullable analytical reason
        // code.

        var reasonlessFact =
                factWith(
                        ACTOR_SUBJECT,
                        AFFECTED_SUBJECT,
                        BASE_ACTION,
                        BASE_OUTCOME,
                        null,
                        BASE_OCCURRED_AT,
                        baseFact().schemaVersion());

        repository.append(
                reasonlessFact);

        assertThat(persistedFact().reasonCode())
                .as("A fact without a reason must persist a null reason code")
                .isNull();

        assertThatCode(() ->
                repository.append(
                        reasonlessFact))
                .as("Replaying a fact without a reason code must not be"
                        + " mistaken for a content conflict")
                .doesNotThrowAnyException();

        assertThat(rowCountForIdentity())
                .as("Replaying a reasonless fact must not create a second row")
                .isEqualTo(1);

        assertThat(persistedFact())
                .as("Replaying a reasonless fact must leave it unchanged")
                .isEqualTo(
                        representationOf(
                                reasonlessFact));
    }

    @Test
    void replaysASubMicrosecondOccurrenceTimeWithoutFalseConflict() {
        // Why: an operational occurrence time may carry more precision than
        // PostgreSQL TIMESTAMPTZ can store, so comparing a candidate's Java
        // Instant against the reduced persisted value in Java would reject the
        // second delivery of a fact the producer never changed, making
        // at-least-once retry unsafe for such facts.
        // Covers: exact replay of one fact whose occurrence time is not
        // microsecond-aligned.
        // Prevents: conflict comparison drifting out of PostgreSQL into Java,
        // where the two sides would be read at different precisions.
        //
        // The persisted value is deliberately never compared against the
        // original Instant, because that equality is not the contract and
        // cannot hold. Only database representations are compared to each
        // other.

        var subMicrosecondFact =
                factWith(
                        ACTOR_SUBJECT,
                        AFFECTED_SUBJECT,
                        BASE_ACTION,
                        BASE_OUTCOME,
                        BASE_REASON_CODE,
                        SUB_MICROSECOND_OCCURRED_AT,
                        baseFact().schemaVersion());

        repository.append(
                subMicrosecondFact);

        var persistedAfterFirstAppend =
                persistedFact().occurredAt();

        assertThatCode(() ->
                repository.append(
                        subMicrosecondFact))
                .as("Replaying an unchanged fact must not become a conflict"
                        + " merely because storage holds less precision than"
                        + " the domain value expresses")
                .doesNotThrowAnyException();

        assertThat(rowCountForIdentity())
                .as("Replaying a sub-microsecond fact must not create a second"
                        + " row")
                .isEqualTo(1);

        assertThat(persistedFact().occurredAt())
                .as("The stored occurrence time must be identical before and"
                        + " after the replay")
                .isEqualTo(persistedAfterFirstAppend);
    }

    /**
     * Builds the accepted fact through the contract's own convenience
     * constructor, so fact type and schema version are the ones the contract
     * declares rather than values restated by this test.
     */
    private static WorkforceAuthorityChangeFact baseFact() {

        return new WorkforceAuthorityChangeFact(
                SOURCE_EVENT_ID,
                TENANT_ID,
                ACTOR_SUBJECT,
                AFFECTED_SUBJECT,
                BASE_ACTION,
                BASE_OUTCOME,
                BASE_REASON_CODE,
                BASE_OCCURRED_AT);
    }

    /**
     * Enumerates one valid fact per divergent non-key component.
     *
     * <p>
     * Every candidate is a legal {@link WorkforceAuthorityChangeFact} and would
     * satisfy every accepted V22 CHECK constraint, so a rejection can only mean
     * semantic identity conflict and never malformed SQL state.
     * </p>
     */
    private static List<DivergentCandidate> divergentCandidates(
            WorkforceAuthorityChangeFact baseFact) {

        return List.of(
                new DivergentCandidate(
                        "A divergent schema version",
                        factWith(
                                ACTOR_SUBJECT,
                                AFFECTED_SUBJECT,
                                BASE_ACTION,
                                BASE_OUTCOME,
                                BASE_REASON_CODE,
                                BASE_OCCURRED_AT,
                                baseFact.schemaVersion() + 1)),

                new DivergentCandidate(
                        "A divergent actor analytical subject",
                        factWith(
                                new AnalyticalSubjectKey(
                                        UUID.fromString(
                                                "00000000-0000-4000-8000-00000000c002")),
                                AFFECTED_SUBJECT,
                                BASE_ACTION,
                                BASE_OUTCOME,
                                BASE_REASON_CODE,
                                BASE_OCCURRED_AT,
                                baseFact.schemaVersion())),

                new DivergentCandidate(
                        "A divergent affected analytical subject",
                        factWith(
                                ACTOR_SUBJECT,
                                new AnalyticalSubjectKey(
                                        UUID.fromString(
                                                "00000000-0000-4000-8000-00000000d002")),
                                BASE_ACTION,
                                BASE_OUTCOME,
                                BASE_REASON_CODE,
                                BASE_OCCURRED_AT,
                                baseFact.schemaVersion())),

                new DivergentCandidate(
                        "A divergent action",
                        factWith(
                                ACTOR_SUBJECT,
                                AFFECTED_SUBJECT,
                                WorkforceAuthorityChangeAction.PRIVILEGED_MUTATION,
                                BASE_OUTCOME,
                                BASE_REASON_CODE,
                                BASE_OCCURRED_AT,
                                baseFact.schemaVersion())),

                new DivergentCandidate(
                        "A divergent outcome",
                        factWith(
                                ACTOR_SUBJECT,
                                AFFECTED_SUBJECT,
                                BASE_ACTION,
                                WorkforceAuthorityChangeOutcome.DENIED,
                                BASE_REASON_CODE,
                                BASE_OCCURRED_AT,
                                baseFact.schemaVersion())),

                new DivergentCandidate(
                        "A divergent bounded reason code",
                        factWith(
                                ACTOR_SUBJECT,
                                AFFECTED_SUBJECT,
                                BASE_ACTION,
                                BASE_OUTCOME,
                                "AUTHORITY_BAND_LOWERED",
                                BASE_OCCURRED_AT,
                                baseFact.schemaVersion())),

                new DivergentCandidate(
                        "A divergent occurrence time",
                        factWith(
                                ACTOR_SUBJECT,
                                AFFECTED_SUBJECT,
                                BASE_ACTION,
                                BASE_OUTCOME,
                                BASE_REASON_CODE,
                                DIVERGENT_OCCURRED_AT,
                                baseFact.schemaVersion())));
    }

    /**
     * Builds a fact that always reuses the accepted identity components, so a
     * candidate can only differ outside the persisted primary key.
     */
    private static WorkforceAuthorityChangeFact factWith(
            AnalyticalSubjectKey actorSubject,
            AnalyticalSubjectKey affectedSubject,
            WorkforceAuthorityChangeAction action,
            WorkforceAuthorityChangeOutcome outcome,
            String reasonCode,
            Instant occurredAt,
            int schemaVersion) {

        return new WorkforceAuthorityChangeFact(
                SOURCE_EVENT_ID,
                TENANT_ID,
                actorSubject,
                affectedSubject,
                action,
                outcome,
                reasonCode,
                occurredAt,
                AnalyticalFactType.WORKFORCE_AUTHORITY_CHANGE,
                schemaVersion);
    }

    /**
     * Expresses the exact storage representation the fact contract requires,
     * so the assertion states the Java-to-column mapping rather than restating
     * fixture literals.
     */
    private static PersistedFact representationOf(
            WorkforceAuthorityChangeFact fact) {

        return new PersistedFact(
                fact.tenantId(),
                fact.sourceEventId(),
                fact.factType().name(),
                fact.schemaVersion(),
                fact.actorSubject().value(),
                fact.affectedSubject().value(),
                fact.action().name(),
                fact.outcome().name(),
                fact.reasonCode(),
                fact.occurredAt());
    }

    private static PersistedFact persistedFact() {

        return jdbcTemplate.queryForObject(
                """
                SELECT
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
                FROM analytics.workforce_authority_change_facts
                WHERE tenant_id = ?
                  AND source_event_id = ?
                  AND fact_type = ?
                """,
                (resultSet, rowNumber) ->
                        mapPersistedFact(
                                resultSet),
                TENANT_ID,
                SOURCE_EVENT_ID,
                AnalyticalFactType.WORKFORCE_AUTHORITY_CHANGE.name());
    }

    private static PersistedFact mapPersistedFact(
            ResultSet resultSet)
            throws SQLException {

        return new PersistedFact(
                resultSet.getObject(
                        "tenant_id",
                        UUID.class),
                resultSet.getObject(
                        "source_event_id",
                        UUID.class),
                resultSet.getString(
                        "fact_type"),
                resultSet.getInt(
                        "schema_version"),
                resultSet.getObject(
                        "actor_subject_key",
                        UUID.class),
                resultSet.getObject(
                        "affected_subject_key",
                        UUID.class),
                resultSet.getString(
                        "action"),
                resultSet.getString(
                        "outcome"),
                resultSet.getString(
                        "reason_code"),
                resultSet.getTimestamp(
                                "occurred_at")
                        .toInstant());
    }

    private static int rowCountForIdentity() {

        return jdbcTemplate.queryForObject(
                """
                SELECT count(*)
                FROM analytics.workforce_authority_change_facts
                WHERE tenant_id = ?
                  AND source_event_id = ?
                  AND fact_type = ?
                """,
                Integer.class,
                TENANT_ID,
                SOURCE_EVENT_ID,
                AnalyticalFactType.WORKFORCE_AUTHORITY_CHANGE.name());
    }

    private static int totalRowCount() {

        return jdbcTemplate.queryForObject(
                """
                SELECT count(*)
                FROM analytics.workforce_authority_change_facts
                """,
                Integer.class);
    }

    private record DivergentCandidate(
            String label,
            WorkforceAuthorityChangeFact fact) {
    }

    private record PersistedFact(
            UUID tenantId,
            UUID sourceEventId,
            String factType,
            int schemaVersion,
            UUID actorSubjectKey,
            UUID affectedSubjectKey,
            String action,
            String outcome,
            String reasonCode,
            Instant occurredAt) {
    }
}
