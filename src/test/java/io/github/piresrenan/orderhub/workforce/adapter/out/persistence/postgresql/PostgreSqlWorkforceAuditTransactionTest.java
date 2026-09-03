package io.github.piresrenan.orderhub.workforce.adapter.out.persistence.postgresql;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class PostgreSqlWorkforceAuditTransactionTest {

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
    void cleanWorkforceState() {

        if (auditTableExists()) {
            jdbcTemplate.execute(
                    """
                    TRUNCATE TABLE
                        workforce.audit_events,
                        workforce.reporting_relationships,
                        workforce.staff_placements,
                        workforce.job_position_permissions,
                        workforce.job_positions,
                        workforce.departments,
                        workforce.staff_profiles
                    CASCADE
                    """);

            return;
        }

        jdbcTemplate.execute(
                """
                TRUNCATE TABLE
                    workforce.reporting_relationships,
                    workforce.staff_placements,
                    workforce.job_position_permissions,
                    workforce.job_positions,
                    workforce.departments,
                    workforce.staff_profiles
                CASCADE
                """);
    }

    @Test
    void committedStaffDeactivationAndAuditEvidenceCommitTogether() {

        requireAuditStorage();

        var tenantId = UUID.randomUUID();
        var staffId = UUID.randomUUID();
        var actorStaffId = UUID.randomUUID();
        var auditEventId = UUID.randomUUID();

        insertActiveStaff(staffId, tenantId);

        transactionTemplate.executeWithoutResult(status -> {
            deactivateStaff(staffId, tenantId);

            insertAuditEvent(
                    auditEventId,
                    tenantId,
                    actorStaffId,
                    staffId,
                    "STAFF_DEACTIVATED",
                    "APPLIED",
                    null,
                    "ACTIVE",
                    "INACTIVE");
        });

        assertThat(staffStatus(staffId, tenantId))
                .isEqualTo("INACTIVE");

        assertThat(auditEventCount(auditEventId))
                .isEqualTo(1);
    }

    @Test
    void rolledBackStaffDeactivationCannotLeaveDurableAuditEvidence() {

        requireAuditStorage();

        var tenantId = UUID.randomUUID();
        var staffId = UUID.randomUUID();
        var actorStaffId = UUID.randomUUID();
        var auditEventId = UUID.randomUUID();

        insertActiveStaff(staffId, tenantId);

        try {
            transactionTemplate.executeWithoutResult(status -> {
                deactivateStaff(staffId, tenantId);

                insertAuditEvent(
                        auditEventId,
                        tenantId,
                        actorStaffId,
                        staffId,
                        "STAFF_DEACTIVATED",
                        "APPLIED",
                        null,
                        "ACTIVE",
                        "INACTIVE");

                throw new ExpectedRollback();
            });

            throw new AssertionError(
                    "Expected transaction rollback signal");

        } catch (ExpectedRollback expected) {
            // Expected.
        }

        assertThat(staffStatus(staffId, tenantId))
                .isEqualTo("ACTIVE");

        assertThat(auditEventCount(auditEventId))
                .isZero();
    }

    @Test
    void auditEvidenceCannotBeUpdated() {

        requireAuditStorage();

        var tenantId = UUID.randomUUID();
        var staffId = UUID.randomUUID();
        var auditEventId = UUID.randomUUID();

        insertAuditEvent(
                auditEventId,
                tenantId,
                UUID.randomUUID(),
                staffId,
                "PRIVILEGED_MUTATION",
                "DENIED",
                "DELEGATION_ENVELOPE_EXCEEDED",
                null,
                null);

        assertAppendOnlyRejection(() ->
                jdbcTemplate.update(
                        """
                        UPDATE workforce.audit_events
                        SET reason_code = 'MUTATED'
                        WHERE audit_event_id = ?
                        """,
                        auditEventId));

        assertThat(auditEventCount(auditEventId))
                .isEqualTo(1);
    }

    @Test
    void auditEvidenceCannotBeDeleted() {

        requireAuditStorage();

        var tenantId = UUID.randomUUID();
        var staffId = UUID.randomUUID();
        var auditEventId = UUID.randomUUID();

        insertAuditEvent(
                auditEventId,
                tenantId,
                UUID.randomUUID(),
                staffId,
                "PRIVILEGED_MUTATION",
                "DENIED",
                "INSUFFICIENT_AUTHORITY_BAND",
                null,
                null);

        assertAppendOnlyRejection(() ->
                jdbcTemplate.update(
                        """
                        DELETE FROM workforce.audit_events
                        WHERE audit_event_id = ?
                        """,
                        auditEventId));

        assertThat(auditEventCount(auditEventId))
                .isEqualTo(1);
    }

    @Test
    void auditSchemaContainsOnlyBoundedOperationalColumns() {

        requireAuditStorage();

        var columns =
                jdbcTemplate.queryForList(
                        """
                        SELECT column_name
                        FROM information_schema.columns
                        WHERE table_schema = 'workforce'
                          AND table_name = 'audit_events'
                        ORDER BY ordinal_position
                        """,
                        String.class);

        assertThat(columns)
                .containsExactly(
                        "audit_event_id",
                        "tenant_id",
                        "actor_staff_id",
                        "affected_staff_id",
                        "action_type",
                        "outcome",
                        "reason_code",
                        "correlation_id",
                        "before_status",
                        "after_status",
                        "before_department_id",
                        "after_department_id",
                        "before_position_id",
                        "after_position_id",
                        "before_supervisor_staff_id",
                        "after_supervisor_staff_id",
                        "before_authority_band",
                        "after_authority_band",
                        "occurred_at");
    }

    private void requireAuditStorage() {

        assertThat(auditTableExists())
                .as("V16 workforce audit storage must exist")
                .isTrue();
    }

    private boolean auditTableExists() {

        var exists =
                jdbcTemplate.queryForObject(
                        """
                        SELECT to_regclass(
                            'workforce.audit_events'
                        ) IS NOT NULL
                        """,
                        Boolean.class);

        return Boolean.TRUE.equals(exists);
    }

    private void insertActiveStaff(
            UUID staffId,
            UUID tenantId) {

        jdbcTemplate.update(
                """
                INSERT INTO workforce.staff_profiles (
                    staff_id,
                    user_id,
                    tenant_id,
                    status
                )
                VALUES (?, ?, ?, 'ACTIVE')
                """,
                staffId,
                UUID.randomUUID(),
                tenantId);
    }

    private void deactivateStaff(
            UUID staffId,
            UUID tenantId) {

        jdbcTemplate.update(
                """
                UPDATE workforce.staff_profiles
                SET status = 'INACTIVE'
                WHERE staff_id = ?
                  AND tenant_id = ?
                """,
                staffId,
                tenantId);
    }

    private void insertAuditEvent(
            UUID auditEventId,
            UUID tenantId,
            UUID actorStaffId,
            UUID affectedStaffId,
            String actionType,
            String outcome,
            String reasonCode,
            String beforeStatus,
            String afterStatus) {

        jdbcTemplate.update(
                """
                INSERT INTO workforce.audit_events (
                    audit_event_id,
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
                    before_supervisor_staff_id,
                    after_supervisor_staff_id,
                    before_authority_band,
                    after_authority_band
                )
                VALUES (
                    ?, ?, ?, ?, ?, ?, ?, ?,
                    ?, ?, NULL, NULL, NULL, NULL,
                    NULL, NULL, NULL, NULL
                )
                """,
                auditEventId,
                tenantId,
                actorStaffId,
                affectedStaffId,
                actionType,
                outcome,
                reasonCode,
                UUID.randomUUID(),
                beforeStatus,
                afterStatus);
    }

    private String staffStatus(
            UUID staffId,
            UUID tenantId) {

        return jdbcTemplate.queryForObject(
                """
                SELECT status
                FROM workforce.staff_profiles
                WHERE staff_id = ?
                  AND tenant_id = ?
                """,
                String.class,
                staffId,
                tenantId);
    }

    private int auditEventCount(
            UUID auditEventId) {

        var count =
                jdbcTemplate.queryForObject(
                        """
                        SELECT COUNT(*)
                        FROM workforce.audit_events
                        WHERE audit_event_id = ?
                        """,
                        Integer.class,
                        auditEventId);

        return count == null ? 0 : count;
    }

    private void assertAppendOnlyRejection(
            Runnable mutation) {

        try {
            mutation.run();

            throw new AssertionError(
                    "Expected append-only audit rejection");

        } catch (DataAccessException exception) {

            var sqlException =
                    findSqlException(exception);

            assertThat((Object) sqlException)
                    .isNotNull();

            assertThat(sqlException.getSQLState())
                    .isEqualTo("23514");

            assertThat(
                    exception.getMostSpecificCause()
                            .getMessage())
                    .contains(
                            "Workforce audit evidence is append-only");
        }
    }

    private SQLException findSqlException(
            Throwable throwable) {

        var current = throwable;

        while (current != null) {

            if (current instanceof SQLException sqlException) {
                return sqlException;
            }

            current = current.getCause();
        }

        return null;
    }

    private static final class ExpectedRollback
            extends RuntimeException {

        private static final long serialVersionUID = 1L;
    }
}
