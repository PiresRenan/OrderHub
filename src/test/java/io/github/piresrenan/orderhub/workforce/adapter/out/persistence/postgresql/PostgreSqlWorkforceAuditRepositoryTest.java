package io.github.piresrenan.orderhub.workforce.adapter.out.persistence.postgresql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import io.github.piresrenan.orderhub.authorization.domain.model.AuthorityBand;
import io.github.piresrenan.orderhub.workforce.application.model.WorkforceAuditActionType;
import io.github.piresrenan.orderhub.workforce.application.model.WorkforceAuditEvidence;
import io.github.piresrenan.orderhub.workforce.application.model.WorkforceAuditOutcome;
import io.github.piresrenan.orderhub.workforce.application.model.WorkforceAuditState;
import io.github.piresrenan.orderhub.workforce.application.port.out.WorkforceAuditPersistenceException;
import io.github.piresrenan.orderhub.workforce.application.port.out.WorkforceAuditRepository;
import io.github.piresrenan.orderhub.workforce.domain.model.StaffStatus;

@Testcontainers
class PostgreSqlWorkforceAuditRepositoryTest {

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

    private static JdbcTemplate jdbcTemplate;

    private static TransactionTemplate transactionTemplate;

    private PostgreSqlWorkforceAuditRepository repository;

    @BeforeAll
    static void migrateSchema() {

        DataSource dataSource =
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
                new JdbcTemplate(dataSource);

        transactionTemplate =
                new TransactionTemplate(
                        new JdbcTransactionManager(dataSource));
    }

    @BeforeEach
    void resetAuditStorage() {

        jdbcTemplate.update(
                "TRUNCATE TABLE workforce.audit_events");

        repository =
                new PostgreSqlWorkforceAuditRepository(
                        jdbcTemplate);
    }

    @Test
    void repositoryPortExposesAppendOnlyWriteSurface() {

        var methods =
                WorkforceAuditRepository.class
                        .getDeclaredMethods();

        assertThat(methods)
                .hasSize(1);

        assertThat(methods[0].getName())
                .isEqualTo("append");

        assertThat(methods[0].getReturnType())
                .isEqualTo(void.class);

        assertThat(methods[0].getParameterTypes())
                .containsExactly(
                        WorkforceAuditEvidence.class);
    }

    @Test
    void appendPersistsBoundedEvidenceAndDatabaseTimestamp() {

        var eventId = UUID.randomUUID();
        var tenantId = UUID.randomUUID();
        var actorStaffId = UUID.randomUUID();
        var affectedStaffId = UUID.randomUUID();
        var correlationId = UUID.randomUUID();
        var beforeDepartmentId = UUID.randomUUID();
        var afterDepartmentId = UUID.randomUUID();
        var positionId = UUID.randomUUID();

        var evidence =
                new WorkforceAuditEvidence(
                        eventId,
                        tenantId,
                        actorStaffId,
                        affectedStaffId,
                        WorkforceAuditActionType.DEPARTMENT_CHANGED,
                        WorkforceAuditOutcome.APPLIED,
                        null,
                        correlationId,
                        new WorkforceAuditState(
                                StaffStatus.ACTIVE,
                                beforeDepartmentId,
                                positionId,
                                null,
                                AuthorityBand.OPERATIONAL),
                        new WorkforceAuditState(
                                StaffStatus.ACTIVE,
                                afterDepartmentId,
                                positionId,
                                null,
                                AuthorityBand.OPERATIONAL));

        repository.append(evidence);

        var row =
                jdbcTemplate.queryForMap(
                        """
                        SELECT
                            tenant_id,
                            actor_staff_id,
                            affected_staff_id,
                            action_type,
                            outcome,
                            reason_code,
                            correlation_id,
                            before_status,
                            after_status,
                            before_department_id,
                            after_department_id,
                            before_position_id,
                            after_position_id,
                            before_authority_band,
                            after_authority_band,
                            occurred_at
                        FROM workforce.audit_events
                        WHERE audit_event_id = ?
                        """,
                        eventId);

        assertThat(row.get("tenant_id"))
                .isEqualTo(tenantId);

        assertThat(row.get("actor_staff_id"))
                .isEqualTo(actorStaffId);

        assertThat(row.get("affected_staff_id"))
                .isEqualTo(affectedStaffId);

        assertThat(row.get("action_type"))
                .isEqualTo("DEPARTMENT_CHANGED");

        assertThat(row.get("outcome"))
                .isEqualTo("APPLIED");

        assertThat(row.get("reason_code"))
                .isNull();

        assertThat(row.get("correlation_id"))
                .isEqualTo(correlationId);

        assertThat(row.get("before_status"))
                .isEqualTo("ACTIVE");

        assertThat(row.get("after_status"))
                .isEqualTo("ACTIVE");

        assertThat(row.get("before_department_id"))
                .isEqualTo(beforeDepartmentId);

        assertThat(row.get("after_department_id"))
                .isEqualTo(afterDepartmentId);

        assertThat(row.get("before_position_id"))
                .isEqualTo(positionId);

        assertThat(row.get("after_position_id"))
                .isEqualTo(positionId);

        assertThat(row.get("before_authority_band"))
                .isEqualTo("OPERATIONAL");

        assertThat(row.get("after_authority_band"))
                .isEqualTo("OPERATIONAL");

        assertThat(row.get("occurred_at"))
                .isNotNull();
    }

    @Test
    void callerRollbackAlsoRollsBackRepositoryAppend() {

        var eventId = UUID.randomUUID();

        try {
            transactionTemplate.executeWithoutResult(status -> {
                repository.append(
                        sampleEvidence(
                                eventId));

                throw new ExpectedRollback();
            });

            throw new AssertionError(
                    "Expected caller rollback");

        } catch (ExpectedRollback expected) {
            // Expected.
        }

        assertThat(auditCount(eventId))
                .isZero();
    }

    @Test
    void invalidReasonCodeFailsBeforePersistence() {

        assertThatThrownBy(() ->
                new WorkforceAuditEvidence(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        WorkforceAuditActionType.PRIVILEGED_MUTATION,
                        WorkforceAuditOutcome.DENIED,
                        "free text is not a bounded reason code",
                        UUID.randomUUID(),
                        WorkforceAuditState.empty(),
                        WorkforceAuditState.empty()))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(totalAuditCount())
                .isZero();
    }

    @Test
    void persistenceFailureIsTranslatedToWorkforceBoundary() {

        var eventId = UUID.randomUUID();
        var evidence = sampleEvidence(eventId);

        repository.append(evidence);

        assertThatThrownBy(() ->
                repository.append(evidence))
                .isInstanceOf(
                        WorkforceAuditPersistenceException.class);

        assertThat(auditCount(eventId))
                .isEqualTo(1);
    }

    @Test
    void actionTypeVocabularyMatchesPersistedV16Vocabulary() {

        assertThat(WorkforceAuditActionType.values())
                .extracting(value -> value.name())
                .containsExactlyInAnyOrder(
                        "STAFF_ACTIVATED",
                        "STAFF_DEACTIVATED",
                        "DEPARTMENT_CHANGED",
                        "POSITION_CHANGED",
                        "POSITION_AUTHORITY_CHANGED",
                        "SUPERVISOR_CHANGED",
                        "PRIVILEGED_MUTATION");
    }

    @Test
    void reasonCodeMinimumLengthMatchesPersistedV16Constraint() {

        assertThatThrownBy(() ->
                new WorkforceAuditEvidence(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        WorkforceAuditActionType.PRIVILEGED_MUTATION,
                        WorkforceAuditOutcome.DENIED,
                        "A",
                        UUID.randomUUID(),
                        WorkforceAuditState.empty(),
                        WorkforceAuditState.empty()))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() ->
                new WorkforceAuditEvidence(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        WorkforceAuditActionType.PRIVILEGED_MUTATION,
                        WorkforceAuditOutcome.DENIED,
                        "AB",
                        UUID.randomUUID(),
                        WorkforceAuditState.empty(),
                        WorkforceAuditState.empty()))
                .isInstanceOf(IllegalArgumentException.class);
    }
    private WorkforceAuditEvidence sampleEvidence(
            UUID eventId) {

        return new WorkforceAuditEvidence(
                eventId,
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                WorkforceAuditActionType.PRIVILEGED_MUTATION,
                WorkforceAuditOutcome.DENIED,
                "INSUFFICIENT_AUTHORITY_BAND",
                UUID.randomUUID(),
                WorkforceAuditState.empty(),
                WorkforceAuditState.empty());
    }

    private int auditCount(
            UUID eventId) {

        var count =
                jdbcTemplate.queryForObject(
                        """
                        SELECT COUNT(*)
                        FROM workforce.audit_events
                        WHERE audit_event_id = ?
                        """,
                        Integer.class,
                        eventId);

        return count == null ? 0 : count;
    }

    private int totalAuditCount() {

        var count =
                jdbcTemplate.queryForObject(
                        """
                        SELECT COUNT(*)
                        FROM workforce.audit_events
                        """,
                        Integer.class);

        return count == null ? 0 : count;
    }

    private static final class ExpectedRollback
            extends RuntimeException {

        private static final long serialVersionUID = 1L;
    }
}
