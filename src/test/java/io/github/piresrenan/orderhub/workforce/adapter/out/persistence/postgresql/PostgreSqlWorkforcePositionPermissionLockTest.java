package io.github.piresrenan.orderhub.workforce.adapter.out.persistence.postgresql;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;

import io.github.piresrenan.orderhub.OrderHubApplication;
import io.github.piresrenan.orderhub.support.PostgreSqlTestConfiguration;
import io.github.piresrenan.orderhub.workforce.application.port.out.WorkforceTransactionExecutor;

@SpringBootTest
@ContextConfiguration(
        classes = {
            OrderHubApplication.class,
            PostgreSqlTestConfiguration.class
        })
class PostgreSqlWorkforcePositionPermissionLockTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private WorkforceTransactionExecutor transactionExecutor;

    @BeforeEach
    void cleanWorkforceState() {

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
    }

    @Test
    void permissionUpdateWaitsForAuthoritativePositionSnapshot()
            throws Exception {

        var fixture = createFixture();

        var authoritativeSnapshotLoaded =
                new CountDownLatch(1);

        var releaseAuthoritativeSnapshot =
                new CountDownLatch(1);

        var repository =
                new PostgreSqlWorkforcePositionChangeRepository(
                        jdbcTemplate);

        var snapshotReader =
                CompletableFuture.supplyAsync(() ->
                        transactionExecutor.execute(() -> {

                            var snapshot =
                                    repository.loadForUpdate(
                                            fixture.tenantId(),
                                            fixture.actorStaffId(),
                                            fixture.targetStaffId(),
                                            fixture.managementPositionId());

                            assertThat(
                                    snapshot.requestedTargetPosition()
                                            .permissionEnvelope()
                                            .codes())
                                    .extracting(Enum::name)
                                    .containsExactly(
                                            "CATALOG_VIEW");

                            authoritativeSnapshotLoaded.countDown();

                            await(
                                    releaseAuthoritativeSnapshot);

                            return snapshot;
                        }));

        assertThat(
                authoritativeSnapshotLoaded.await(
                        5,
                        TimeUnit.SECONDS))
                .as(
                        "authoritative position snapshot must be loaded before competing permission mutation")
                .isTrue();

        var permissionMutation =
                CompletableFuture.supplyAsync(() ->
                        jdbcTemplate.update(
                                """
                                UPDATE workforce.job_position_permissions
                                SET permission_code = 'CATALOG_MANAGE'
                                WHERE tenant_id = ?
                                  AND position_id = ?
                                  AND permission_code = 'CATALOG_VIEW'
                                """,
                                fixture.tenantId(),
                                fixture.managementPositionId()));

        var mutationWaitedForDatabaseLock =
                awaitPermissionMutationLockWait();

        releaseAuthoritativeSnapshot.countDown();

        snapshotReader.get(
                10,
                TimeUnit.SECONDS);

        var updated =
                permissionMutation.get(
                        10,
                        TimeUnit.SECONDS);

        assertThat(mutationWaitedForDatabaseLock)
                .as(
                        "concurrent position permission update must wait for the authoritative PostgreSQL snapshot")
                .isTrue();

        assertThat(updated)
                .isEqualTo(1);

        assertThat(
                jdbcTemplate.queryForList(
                        """
                        SELECT permission_code
                        FROM workforce.job_position_permissions
                        WHERE tenant_id = ?
                          AND position_id = ?
                        ORDER BY permission_code
                        """,
                        String.class,
                        fixture.tenantId(),
                        fixture.managementPositionId()))
                .containsExactly(
                        "CATALOG_MANAGE");
    }

    private boolean awaitPermissionMutationLockWait() {

        var deadline =
                System.nanoTime()
                        + TimeUnit.SECONDS.toNanos(5);

        while (System.nanoTime() < deadline) {

            var waiting =
                    jdbcTemplate.queryForObject(
                            """
                            SELECT EXISTS (
                                SELECT 1
                                FROM pg_stat_activity
                                WHERE datname = current_database()
                                  AND pid <> pg_backend_pid()
                                  AND wait_event_type = 'Lock'
                                  AND query LIKE
                                        '%UPDATE workforce.job_position_permissions%'
                            )
                            """,
                            Boolean.class);

            if (Boolean.TRUE.equals(waiting)) {
                return true;
            }

            try {
                Thread.sleep(10);

            } catch (InterruptedException exception) {

                Thread.currentThread()
                        .interrupt();

                throw new IllegalStateException(
                        "Interrupted while observing permission-row lock wait",
                        exception);
            }
        }

        return false;
    }

    private Fixture createFixture() {

        var tenantId = UUID.randomUUID();
        var departmentId = UUID.randomUUID();
        var actorStaffId = UUID.randomUUID();
        var targetStaffId = UUID.randomUUID();
        var governancePositionId = UUID.randomUUID();
        var operationalPositionId = UUID.randomUUID();
        var managementPositionId = UUID.randomUUID();

        jdbcTemplate.update(
                """
                INSERT INTO workforce.departments (
                    department_id,
                    tenant_id,
                    code,
                    name
                )
                VALUES (?, ?, 'OPS', 'Operations')
                """,
                departmentId,
                tenantId);

        insertPosition(
                governancePositionId,
                tenantId,
                "GOV",
                "Tenant Governance",
                "TENANT_GOVERNANCE");

        insertPosition(
                operationalPositionId,
                tenantId,
                "OPS",
                "Operations",
                "OPERATIONAL");

        insertPosition(
                managementPositionId,
                tenantId,
                "MGT",
                "Management",
                "MANAGEMENT");

        insertPermission(
                tenantId,
                governancePositionId,
                "CATALOG_VIEW");

        insertPermission(
                tenantId,
                operationalPositionId,
                "CATALOG_VIEW");

        insertPermission(
                tenantId,
                managementPositionId,
                "CATALOG_VIEW");

        insertStaff(
                actorStaffId,
                tenantId);

        insertStaff(
                targetStaffId,
                tenantId);

        insertPlacement(
                tenantId,
                actorStaffId,
                departmentId,
                governancePositionId);

        insertPlacement(
                tenantId,
                targetStaffId,
                departmentId,
                operationalPositionId);

        return new Fixture(
                tenantId,
                actorStaffId,
                targetStaffId,
                managementPositionId);
    }

    private void insertPosition(
            UUID positionId,
            UUID tenantId,
            String code,
            String title,
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
                title,
                authorityBand);
    }

    private void insertPermission(
            UUID tenantId,
            UUID positionId,
            String permissionCode) {

        jdbcTemplate.update(
                """
                INSERT INTO workforce.job_position_permissions (
                    tenant_id,
                    position_id,
                    permission_code
                )
                VALUES (?, ?, ?)
                """,
                tenantId,
                positionId,
                permissionCode);
    }

    private void insertStaff(
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

    private static void await(
            CountDownLatch latch) {

        try {
            if (!latch.await(
                    10,
                    TimeUnit.SECONDS)) {

                throw new IllegalStateException(
                        "Timed out waiting to release authoritative snapshot");
            }

        } catch (InterruptedException exception) {

            Thread.currentThread()
                    .interrupt();

            throw new IllegalStateException(
                    "Interrupted while holding authoritative snapshot locks",
                    exception);
        }
    }

    private record Fixture(
            UUID tenantId,
            UUID actorStaffId,
            UUID targetStaffId,
            UUID managementPositionId) {
    }
}
