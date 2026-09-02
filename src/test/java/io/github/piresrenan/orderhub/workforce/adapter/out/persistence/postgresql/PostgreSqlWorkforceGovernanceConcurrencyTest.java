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
class PostgreSqlWorkforceGovernanceConcurrencyTest {

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
    void concurrentPlacementDemotionsCannotRemoveAllViableGovernanceStaff()
            throws Exception {

        var tenantId =
                UUID.randomUUID();

        var departmentId =
                UUID.randomUUID();

        var governancePositionId =
                UUID.randomUUID();

        var managementPositionId =
                UUID.randomUUID();

        var staffA =
                UUID.randomUUID();

        var staffB =
                UUID.randomUUID();

        insertDepartment(
                departmentId,
                tenantId,
                "OPS");

        insertPosition(
                governancePositionId,
                tenantId,
                "GOV",
                "TENANT_GOVERNANCE");

        insertPosition(
                managementPositionId,
                tenantId,
                "MGT",
                "MANAGEMENT");

        insertActiveStaff(
                staffA,
                tenantId);

        insertActiveStaff(
                staffB,
                tenantId);

        insertPlacement(
                tenantId,
                staffA,
                departmentId,
                governancePositionId);

        insertPlacement(
                tenantId,
                staffB,
                departmentId,
                governancePositionId);

        var result =
                executeContendedPair(
                        tenantId,
                        () ->
                                updatePlacementPosition(
                                        tenantId,
                                        staffA,
                                        managementPositionId),
                        () ->
                                updatePlacementPosition(
                                        tenantId,
                                        staffB,
                                        managementPositionId));

        assertThat(
                result.secondWaitedForGovernanceLock())
                .as(
                        "competing governance demotion must actually wait "
                                + "on the Tenant governance advisory lock")
                .isTrue();

        assertCommitted(
                result.firstOutcome());

        assertLastGovernanceRejected(
                result.secondOutcome());

        assertThat(
                viableGovernanceCount(
                        tenantId))
                .isEqualTo(
                        1);
    }

    @Test
    void deactivationAndPlacementDeletionCannotRemoveAllViableGovernanceStaff()
            throws Exception {

        var tenantId =
                UUID.randomUUID();

        var departmentId =
                UUID.randomUUID();

        var governancePositionId =
                UUID.randomUUID();

        var staffA =
                UUID.randomUUID();

        var staffB =
                UUID.randomUUID();

        insertDepartment(
                departmentId,
                tenantId,
                "OPS");

        insertPosition(
                governancePositionId,
                tenantId,
                "GOV",
                "TENANT_GOVERNANCE");

        insertActiveStaff(
                staffA,
                tenantId);

        insertActiveStaff(
                staffB,
                tenantId);

        insertPlacement(
                tenantId,
                staffA,
                departmentId,
                governancePositionId);

        insertPlacement(
                tenantId,
                staffB,
                departmentId,
                governancePositionId);

        var result =
                executeContendedPair(
                        tenantId,
                        () ->
                                deactivateStaff(
                                        tenantId,
                                        staffA),
                        () ->
                                deletePlacement(
                                        tenantId,
                                        staffB));

        assertThat(
                result.secondWaitedForGovernanceLock())
                .as(
                        "placement deletion must wait on the same Tenant "
                                + "governance lock used by Staff deactivation")
                .isTrue();

        assertCommitted(
                result.firstOutcome());

        assertLastGovernanceRejected(
                result.secondOutcome());

        assertThat(
                viableGovernanceCount(
                        tenantId))
                .isEqualTo(
                        1);
    }

    @Test
    void concurrentGovernancePositionBandDowngradesCannotRemoveAllViableGovernanceStaff()
            throws Exception {

        var tenantId =
                UUID.randomUUID();

        var departmentId =
                UUID.randomUUID();

        var governancePositionA =
                UUID.randomUUID();

        var governancePositionB =
                UUID.randomUUID();

        var staffA =
                UUID.randomUUID();

        var staffB =
                UUID.randomUUID();

        insertDepartment(
                departmentId,
                tenantId,
                "OPS");

        insertPosition(
                governancePositionA,
                tenantId,
                "GOV_A",
                "TENANT_GOVERNANCE");

        insertPosition(
                governancePositionB,
                tenantId,
                "GOV_B",
                "TENANT_GOVERNANCE");

        insertActiveStaff(
                staffA,
                tenantId);

        insertActiveStaff(
                staffB,
                tenantId);

        insertPlacement(
                tenantId,
                staffA,
                departmentId,
                governancePositionA);

        insertPlacement(
                tenantId,
                staffB,
                departmentId,
                governancePositionB);

        var result =
                executeContendedPair(
                        tenantId,
                        () ->
                                updatePositionBand(
                                        tenantId,
                                        governancePositionA,
                                        "MANAGEMENT"),
                        () ->
                                updatePositionBand(
                                        tenantId,
                                        governancePositionB,
                                        "MANAGEMENT"));

        assertThat(
                result.secondWaitedForGovernanceLock())
                .as(
                        "competing JobPosition authority downgrade must wait "
                                + "on the Tenant governance advisory lock")
                .isTrue();

        assertCommitted(
                result.firstOutcome());

        assertLastGovernanceRejected(
                result.secondOutcome());

        assertThat(
                viableGovernanceCount(
                        tenantId))
                .isEqualTo(
                        1);
    }

    @Test
    void movingLastGovernancePlacementAcrossTenantsCannotAbandonSourceTenant() {

        var sourceTenantId =
                UUID.randomUUID();

        var targetTenantId =
                UUID.randomUUID();

        var sourceDepartmentId =
                UUID.randomUUID();

        var targetDepartmentId =
                UUID.randomUUID();

        var sourceGovernancePositionId =
                UUID.randomUUID();

        var targetGovernancePositionId =
                UUID.randomUUID();

        var sourceStaffId =
                UUID.randomUUID();

        var targetStaffId =
                UUID.randomUUID();

        insertDepartment(
                sourceDepartmentId,
                sourceTenantId,
                "SOURCE_OPS");

        insertDepartment(
                targetDepartmentId,
                targetTenantId,
                "TARGET_OPS");

        insertPosition(
                sourceGovernancePositionId,
                sourceTenantId,
                "SOURCE_GOV",
                "TENANT_GOVERNANCE");

        insertPosition(
                targetGovernancePositionId,
                targetTenantId,
                "TARGET_GOV",
                "TENANT_GOVERNANCE");

        insertActiveStaff(
                sourceStaffId,
                sourceTenantId);

        insertActiveStaff(
                targetStaffId,
                targetTenantId);

        insertPlacement(
                sourceTenantId,
                sourceStaffId,
                sourceDepartmentId,
                sourceGovernancePositionId);

        var outcome =
                executeMutation(() ->
                        movePlacementAcrossTenants(
                                sourceTenantId,
                                sourceStaffId,
                                targetTenantId,
                                targetStaffId,
                                targetDepartmentId,
                                targetGovernancePositionId));

        assertLastGovernanceRejected(
                outcome);

        assertThat(
                viableGovernanceCount(
                        sourceTenantId))
                .isEqualTo(
                        1);

        assertThat(
                viableGovernanceCount(
                        targetTenantId))
                .isZero();
    }
    private ContendedMutationResult executeContendedPair(
            UUID tenantId,
            Runnable firstMutation,
            Runnable secondMutation)
            throws Exception {

        var firstLockHeld =
                new CountDownLatch(
                        1);

        var releaseFirstMutation =
                new CountDownLatch(
                        1);

        var first =
                CompletableFuture.supplyAsync(() ->
                        executeMutation(() -> {

                            acquireExpectedGovernanceTenantLock(
                                    tenantId);

                            firstLockHeld.countDown();

                            await(
                                    releaseFirstMutation);

                            firstMutation.run();
                        }));

        assertThat(
                firstLockHeld.await(
                        5,
                        TimeUnit.SECONDS))
                .as("first PostgreSQL transaction must hold governance lock")
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

                            secondMutation.run();
                        }));

        assertThat(
                waitingBackendKnown.await(
                        5,
                        TimeUnit.SECONDS))
                .as("competing PostgreSQL backend must be identifiable")
                .isTrue();

        var secondWaited =
                awaitAdvisoryLockWait(
                        waitingBackendPid.get());

        releaseFirstMutation.countDown();

        var firstOutcome =
                first.get(
                        10,
                        TimeUnit.SECONDS);

        var secondOutcome =
                second.get(
                        10,
                        TimeUnit.SECONDS);

        return new ContendedMutationResult(
                secondWaited,
                firstOutcome,
                secondOutcome);
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

    private void assertLastGovernanceRejected(
            MutationOutcome outcome) {

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
                        "Tenant must retain at least one ACTIVE "
                                + "Staff in TENANT_GOVERNANCE");
    }

    private void acquireExpectedGovernanceTenantLock(
            UUID tenantId) {

        jdbcTemplate.execute(
                (ConnectionCallback<Void>) connection -> {

                    try (var statement =
                            connection.prepareStatement(
                                    """
                                    SELECT pg_advisory_xact_lock(
                                        hashtextextended(
                                            'orderhub.workforce.governance:'
                                                || CAST(? AS TEXT),
                                            0
                                        )
                                    )
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
                        "Interrupted while observing governance lock wait",
                        exception);
            }
        }

        return false;
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

    private void insertDepartment(
            UUID departmentId,
            UUID tenantId,
            String code) {

        jdbcTemplate.update(
                """
                INSERT INTO workforce.departments (
                    department_id,
                    tenant_id,
                    code,
                    name
                )
                VALUES (?, ?, ?, ?)
                """,
                departmentId,
                tenantId,
                code,
                code);
    }

    private void insertPosition(
            UUID positionId,
            UUID tenantId,
            String code,
            String authorityBand) {

        jdbcTemplate.update(
                """
                INSERT INTO workforce.job_positions (
                    position_id,
                    tenant_id,
                    code,
                    title,
                    authority_band
                )
                VALUES (?, ?, ?, ?, ?)
                """,
                positionId,
                tenantId,
                code,
                code,
                authorityBand);
    }

    private void insertPlacement(
            UUID tenantId,
            UUID staffId,
            UUID departmentId,
            UUID positionId) {

        jdbcTemplate.update(
                """
                INSERT INTO workforce.staff_placements (
                    tenant_id,
                    staff_id,
                    department_id,
                    position_id
                )
                VALUES (?, ?, ?, ?)
                """,
                tenantId,
                staffId,
                departmentId,
                positionId);
    }

    private void movePlacementAcrossTenants(
            UUID sourceTenantId,
            UUID sourceStaffId,
            UUID targetTenantId,
            UUID targetStaffId,
            UUID targetDepartmentId,
            UUID targetPositionId) {

        jdbcTemplate.update(
                """
                UPDATE workforce.staff_placements
                SET tenant_id = ?,
                    staff_id = ?,
                    department_id = ?,
                    position_id = ?
                WHERE tenant_id = ?
                  AND staff_id = ?
                """,
                targetTenantId,
                targetStaffId,
                targetDepartmentId,
                targetPositionId,
                sourceTenantId,
                sourceStaffId);
    }
    private void updatePlacementPosition(
            UUID tenantId,
            UUID staffId,
            UUID targetPositionId) {

        jdbcTemplate.update(
                """
                UPDATE workforce.staff_placements
                SET position_id = ?
                WHERE tenant_id = ?
                  AND staff_id = ?
                """,
                targetPositionId,
                tenantId,
                staffId);
    }

    private void deletePlacement(
            UUID tenantId,
            UUID staffId) {

        jdbcTemplate.update(
                """
                DELETE FROM workforce.staff_placements
                WHERE tenant_id = ?
                  AND staff_id = ?
                """,
                tenantId,
                staffId);
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

    private void updatePositionBand(
            UUID tenantId,
            UUID positionId,
            String authorityBand) {

        jdbcTemplate.update(
                """
                UPDATE workforce.job_positions
                SET authority_band = ?
                WHERE tenant_id = ?
                  AND position_id = ?
                """,
                authorityBand,
                tenantId,
                positionId);
    }

    private int viableGovernanceCount(
            UUID tenantId) {

        var count =
                jdbcTemplate.queryForObject(
                        """
                        SELECT COUNT(*)
                        FROM workforce.staff_profiles staff
                        JOIN workforce.staff_placements placement
                          ON placement.tenant_id = staff.tenant_id
                         AND placement.staff_id = staff.staff_id
                        JOIN workforce.job_positions position
                          ON position.tenant_id = placement.tenant_id
                         AND position.position_id = placement.position_id
                        WHERE staff.tenant_id = ?
                          AND staff.status = 'ACTIVE'
                          AND position.authority_band =
                                'TENANT_GOVERNANCE'
                        """,
                        Integer.class,
                        tenantId);

        return count == null
                ? 0
                : count;
    }

    private void await(
            CountDownLatch latch) {

        try {
            if (!latch.await(
                    10,
                    TimeUnit.SECONDS)) {

                throw new IllegalStateException(
                        "Timed out while coordinating governance concurrency test");
            }

        } catch (InterruptedException exception) {

            Thread.currentThread()
                    .interrupt();

            throw new IllegalStateException(
                    "Interrupted while coordinating governance concurrency test",
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

    private record ContendedMutationResult(
            boolean secondWaitedForGovernanceLock,
            MutationOutcome firstOutcome,
            MutationOutcome secondOutcome) {
    }
}
