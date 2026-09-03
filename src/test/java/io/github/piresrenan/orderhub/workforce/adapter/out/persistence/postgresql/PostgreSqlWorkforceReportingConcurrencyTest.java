package io.github.piresrenan.orderhub.workforce.adapter.out.persistence.postgresql;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class PostgreSqlWorkforceReportingConcurrencyTest {

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

    private static DataSource dataSource;

    private static JdbcTemplate jdbcTemplate;

    private static TransactionTemplate transactionTemplate;

    @BeforeAll
    static void migrateSchema() {

        dataSource =
                new DriverManagerDataSource(
                        POSTGRES.getJdbcUrl(),
                        POSTGRES.getUsername(),
                        POSTGRES.getPassword());

        Flyway.configure()
                .dataSource(
                        dataSource)
                .locations(
                        "classpath:db/migration")
                .load()
                .migrate();

        jdbcTemplate =
                new JdbcTemplate(
                        dataSource);

        transactionTemplate =
                new TransactionTemplate(
                        new JdbcTransactionManager(
                                dataSource));
    }

    @BeforeEach
    void cleanWorkforceState() {

        jdbcTemplate.update(
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
    void concurrentOpposingReportingEdgesWaitOnTheTenantLockAndRejectTheCycle()
            throws Exception {

        var tenantId =
                UUID.randomUUID();

        var staffA =
                UUID.randomUUID();

        var staffB =
                UUID.randomUUID();

        insertStaff(
                staffA,
                UUID.randomUUID(),
                tenantId);

        insertStaff(
                staffB,
                UUID.randomUUID(),
                tenantId);

        var firstLockHeld =
                new CountDownLatch(
                        1);

        var releaseFirstMutation =
                new CountDownLatch(
                        1);

        var first =
                CompletableFuture.supplyAsync(() ->
                        executeMutation(() -> {

                            acquireReportingTenantLock(
                                    tenantId);

                            firstLockHeld.countDown();

                            await(
                                    releaseFirstMutation);

                            insertReportingRelationship(
                                    tenantId,
                                    staffA,
                                    staffB);
                        }));

        assertThat(
                firstLockHeld.await(
                        5,
                        TimeUnit.SECONDS))
                .as("first transaction must hold the Tenant advisory lock")
                .isTrue();

        var waitingBackendPid =
                new AtomicInteger();

        var waitingBackendKnown =
                new CountDownLatch(
                        1);

        var second =
                CompletableFuture.supplyAsync(() ->
                        executeMutation(() -> {

                            waitingBackendPid.set(
                                    currentBackendPid());

                            waitingBackendKnown.countDown();

                            insertReportingRelationship(
                                    tenantId,
                                    staffB,
                                    staffA);
                        }));

        assertThat(
                waitingBackendKnown.await(
                        5,
                        TimeUnit.SECONDS))
                .as("competing PostgreSQL backend must be identifiable")
                .isTrue();

        assertThat(
                awaitAdvisoryLockWait(
                        waitingBackendPid.get()))
                .as("competing reporting transaction must actually wait on the Tenant advisory lock")
                .isTrue();

        releaseFirstMutation.countDown();

        var firstOutcome =
                first.get(
                        10,
                        TimeUnit.SECONDS);

        var secondOutcome =
                second.get(
                        10,
                        TimeUnit.SECONDS);

        assertCommitted(
                firstOutcome);

        assertRejected(
                secondOutcome,
                "Reporting relationship would create a Tenant workforce cycle");

        assertThat(
                relationshipCount(
                        tenantId))
                .isEqualTo(
                        1);
    }

    @Test
    void supervisorLifecycleAndReportingMutationsWaitOnTheSameTenantLock()
            throws Exception {

        proveReportingCommitRejectsWaitingDeactivation();

        proveDeactivationCommitRejectsWaitingReportingMutation();
    }

    private void proveReportingCommitRejectsWaitingDeactivation()
            throws Exception {

        var tenantId =
                UUID.randomUUID();

        var supervisorStaffId =
                UUID.randomUUID();

        var subordinateStaffId =
                UUID.randomUUID();

        insertStaff(
                supervisorStaffId,
                UUID.randomUUID(),
                tenantId);

        insertStaff(
                subordinateStaffId,
                UUID.randomUUID(),
                tenantId);

        var firstLockHeld =
                new CountDownLatch(
                        1);

        var releaseFirstMutation =
                new CountDownLatch(
                        1);

        var reporting =
                CompletableFuture.supplyAsync(() ->
                        executeMutation(() -> {

                            acquireReportingTenantLock(
                                    tenantId);

                            firstLockHeld.countDown();

                            await(
                                    releaseFirstMutation);

                            insertReportingRelationship(
                                    tenantId,
                                    supervisorStaffId,
                                    subordinateStaffId);
                        }));

        assertThat(
                firstLockHeld.await(
                        5,
                        TimeUnit.SECONDS))
                .isTrue();

        var waitingBackendPid =
                new AtomicInteger();

        var waitingBackendKnown =
                new CountDownLatch(
                        1);

        var deactivation =
                CompletableFuture.supplyAsync(() ->
                        executeMutation(() -> {

                            waitingBackendPid.set(
                                    currentBackendPid());

                            waitingBackendKnown.countDown();

                            deactivateStaff(
                                    tenantId,
                                    supervisorStaffId);
                        }));

        assertThat(
                waitingBackendKnown.await(
                        5,
                        TimeUnit.SECONDS))
                .isTrue();

        assertThat(
                awaitAdvisoryLockWait(
                        waitingBackendPid.get()))
                .as("deactivation must wait on the reporting Tenant lock")
                .isTrue();

        releaseFirstMutation.countDown();

        var reportingOutcome =
                reporting.get(
                        10,
                        TimeUnit.SECONDS);

        var deactivationOutcome =
                deactivation.get(
                        10,
                        TimeUnit.SECONDS);

        assertCommitted(
                reportingOutcome);

        assertRejected(
                deactivationOutcome,
                "Staff with active subordinate relationships cannot become INACTIVE");

        assertThat(
                staffStatus(
                        tenantId,
                        supervisorStaffId))
                .isEqualTo(
                        "ACTIVE");

        assertThat(
                relationshipCountForSupervisor(
                        tenantId,
                        supervisorStaffId))
                .isEqualTo(
                        1);
    }

    private void proveDeactivationCommitRejectsWaitingReportingMutation()
            throws Exception {

        var tenantId =
                UUID.randomUUID();

        var supervisorStaffId =
                UUID.randomUUID();

        var subordinateStaffId =
                UUID.randomUUID();

        insertStaff(
                supervisorStaffId,
                UUID.randomUUID(),
                tenantId);

        insertStaff(
                subordinateStaffId,
                UUID.randomUUID(),
                tenantId);

        var firstLockHeld =
                new CountDownLatch(
                        1);

        var releaseFirstMutation =
                new CountDownLatch(
                        1);

        var deactivation =
                CompletableFuture.supplyAsync(() ->
                        executeMutation(() -> {

                            acquireReportingTenantLock(
                                    tenantId);

                            firstLockHeld.countDown();

                            await(
                                    releaseFirstMutation);

                            deactivateStaff(
                                    tenantId,
                                    supervisorStaffId);
                        }));

        assertThat(
                firstLockHeld.await(
                        5,
                        TimeUnit.SECONDS))
                .isTrue();

        var waitingBackendPid =
                new AtomicInteger();

        var waitingBackendKnown =
                new CountDownLatch(
                        1);

        var reporting =
                CompletableFuture.supplyAsync(() ->
                        executeMutation(() -> {

                            waitingBackendPid.set(
                                    currentBackendPid());

                            waitingBackendKnown.countDown();

                            insertReportingRelationship(
                                    tenantId,
                                    supervisorStaffId,
                                    subordinateStaffId);
                        }));

        assertThat(
                waitingBackendKnown.await(
                        5,
                        TimeUnit.SECONDS))
                .isTrue();

        assertThat(
                awaitAdvisoryLockWait(
                        waitingBackendPid.get()))
                .as("reporting mutation must wait on the lifecycle Tenant lock")
                .isTrue();

        releaseFirstMutation.countDown();

        var deactivationOutcome =
                deactivation.get(
                        10,
                        TimeUnit.SECONDS);

        var reportingOutcome =
                reporting.get(
                        10,
                        TimeUnit.SECONDS);

        assertCommitted(
                deactivationOutcome);

        assertRejected(
                reportingOutcome,
                "Only ACTIVE Staff may remain an active supervisor");

        assertThat(
                staffStatus(
                        tenantId,
                        supervisorStaffId))
                .isEqualTo(
                        "INACTIVE");

        assertThat(
                relationshipCountForSupervisor(
                        tenantId,
                        supervisorStaffId))
                .isZero();
    }

    private MutationOutcome executeMutation(
            Runnable mutation) {

        try {
            transactionTemplate.executeWithoutResult(status ->
                    mutation.run());

            return MutationOutcome.successful();

        } catch (DataAccessException exception) {

            var sqlException =
                    findSqlException(
                            exception);

            return MutationOutcome.rejected(
                    sqlException == null
                            ? null
                            : sqlException.getSQLState(),
                    exception.getMostSpecificCause()
                            .getMessage());
        }
    }

    private SQLException findSqlException(
            Throwable exception) {

        var current =
                exception;

        while (current != null) {

            if (current instanceof SQLException sqlException) {
                return sqlException;
            }

            current =
                    current.getCause();
        }

        return null;
    }

    private void assertCommitted(
            MutationOutcome outcome) {

        assertThat(
                outcome.committed())
                .isTrue();

        assertThat(
                outcome.sqlState())
                .isNull();

        assertThat(
                outcome.message())
                .isNull();
    }

    private void assertRejected(
            MutationOutcome outcome,
            String expectedMessageFragment) {

        assertThat(
                outcome.committed())
                .isFalse();

        assertThat(
                outcome.sqlState())
                .as("PostgreSQL rejection must be check_violation")
                .isEqualTo(
                        "23514");

        assertThat(
                outcome.message())
                .contains(
                        expectedMessageFragment);
    }

    private void acquireReportingTenantLock(
            UUID tenantId) {

        jdbcTemplate.execute(
                (ConnectionCallback<Void>) connection -> {

                    try (var statement =
                            connection.prepareStatement(
                                    """
                                    SELECT workforce.acquire_reporting_tenant_lock(?)
                                    """)) {

                        statement.setObject(
                                1,
                                tenantId);

                        statement.execute();
                    }

                    return null;
                });
    }

    private int currentBackendPid() {

        var pid =
                jdbcTemplate.queryForObject(
                        "SELECT pg_backend_pid()",
                        Integer.class);

        if (pid == null) {
            throw new IllegalStateException(
                    "PostgreSQL backend PID is unavailable");
        }

        return pid;
    }

    private boolean awaitAdvisoryLockWait(
            int backendPid) {

        var deadline =
                System.nanoTime()
                        + TimeUnit.SECONDS.toNanos(
                                5);

        while (System.nanoTime() < deadline) {

            var waiting =
                    jdbcTemplate.queryForObject(
                            """
                            SELECT EXISTS (
                                SELECT 1
                                FROM pg_locks
                                WHERE pid = ?
                                  AND locktype = 'advisory'
                                  AND granted = FALSE
                            )
                            """,
                            Boolean.class,
                            backendPid);

            if (Boolean.TRUE.equals(
                    waiting)) {

                return true;
            }

            try {
                Thread.sleep(
                        10);

            } catch (InterruptedException exception) {

                Thread.currentThread()
                        .interrupt();

                throw new IllegalStateException(
                        "Interrupted while observing PostgreSQL advisory lock wait",
                        exception);
            }
        }

        return false;
    }

    private void insertReportingRelationship(
            UUID tenantId,
            UUID supervisorStaffId,
            UUID subordinateStaffId) {

        jdbcTemplate.update(
                """
                INSERT INTO workforce.reporting_relationships (
                    tenant_id,
                    supervisor_staff_id,
                    subordinate_staff_id
                )
                VALUES (?, ?, ?)
                """,
                tenantId,
                supervisorStaffId,
                subordinateStaffId);
    }

    private void deactivateStaff(
            UUID tenantId,
            UUID staffId) {

        jdbcTemplate.update(
                """
                UPDATE workforce.staff_profiles
                SET status = 'INACTIVE'
                WHERE tenant_id = ?
                  AND staff_id = ?
                """,
                tenantId,
                staffId);
    }

    private void insertStaff(
            UUID staffId,
            UUID userId,
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
                userId,
                tenantId);
    }

    private int relationshipCount(
            UUID tenantId) {

        var count =
                jdbcTemplate.queryForObject(
                        """
                        SELECT COUNT(*)
                        FROM workforce.reporting_relationships
                        WHERE tenant_id = ?
                        """,
                        Integer.class,
                        tenantId);

        return count == null
                ? 0
                : count;
    }

    private int relationshipCountForSupervisor(
            UUID tenantId,
            UUID supervisorStaffId) {

        var count =
                jdbcTemplate.queryForObject(
                        """
                        SELECT COUNT(*)
                        FROM workforce.reporting_relationships
                        WHERE tenant_id = ?
                          AND supervisor_staff_id = ?
                        """,
                        Integer.class,
                        tenantId,
                        supervisorStaffId);

        return count == null
                ? 0
                : count;
    }

    private String staffStatus(
            UUID tenantId,
            UUID staffId) {

        return jdbcTemplate.queryForObject(
                """
                SELECT status
                FROM workforce.staff_profiles
                WHERE tenant_id = ?
                  AND staff_id = ?
                """,
                String.class,
                tenantId,
                staffId);
    }

    private void await(
            CountDownLatch latch) {

        try {
            if (!latch.await(
                    10,
                    TimeUnit.SECONDS)) {

                throw new IllegalStateException(
                        "Timed out while coordinating PostgreSQL concurrency test");
            }

        } catch (InterruptedException exception) {

            Thread.currentThread()
                    .interrupt();

            throw new IllegalStateException(
                    "Interrupted while coordinating PostgreSQL concurrency test",
                    exception);
        }
    }

    private record MutationOutcome(
            boolean committed,
            String sqlState,
            String message) {

        private static MutationOutcome successful() {

            return new MutationOutcome(
                    true,
                    null,
                    null);
        }

        private static MutationOutcome rejected(
                String sqlState,
                String message) {

            return new MutationOutcome(
                    false,
                    sqlState,
                    message);
        }
    }
}
