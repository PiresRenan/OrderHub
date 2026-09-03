package io.github.piresrenan.orderhub.workforce.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;

import io.github.piresrenan.orderhub.OrderHubApplication;
import io.github.piresrenan.orderhub.authorization.domain.model.PermissionCode;
import io.github.piresrenan.orderhub.authorization.domain.model.PermissionEnvelope;
import io.github.piresrenan.orderhub.support.PostgreSqlTestConfiguration;
import io.github.piresrenan.orderhub.workforce.adapter.out.persistence.postgresql.PostgreSqlWorkforceAuditRepository;
import io.github.piresrenan.orderhub.workforce.adapter.out.persistence.postgresql.PostgreSqlWorkforcePositionChangeRepository;
import io.github.piresrenan.orderhub.workforce.application.model.PrivilegedPositionChangeCommand;
import io.github.piresrenan.orderhub.workforce.application.model.WorkforcePositionChangeSnapshot;
import io.github.piresrenan.orderhub.workforce.application.port.out.WorkforcePositionChangeRepository;
import io.github.piresrenan.orderhub.workforce.application.port.out.WorkforceTransactionExecutor;
import io.github.piresrenan.orderhub.workforce.application.service.PrivilegedPositionChangeExecutionService;
import io.github.piresrenan.orderhub.workforce.application.service.PrivilegedWorkforceMutationAuthorizationService;
import io.github.piresrenan.orderhub.workforce.domain.model.WorkforceMutationDecision;

@SpringBootTest
@ContextConfiguration(
        classes = {
            OrderHubApplication.class,
            PostgreSqlTestConfiguration.class
        })
class PrivilegedPositionChangeExecutionAdversarialTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private WorkforceTransactionExecutor transactionExecutor;

    @Autowired
    private PrivilegedPositionChangeExecutionService service;

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
    void samePositionRequestIsDeniedInsteadOfProducingFalseAppliedChange() {

        var fixture = createFixture();

        var auditEventId = UUID.randomUUID();

        var decision =
                service.execute(
                        command(
                                fixture,
                                fixture.operationalPositionId(),
                                auditEventId));

        assertThat(decision)
                .as(
                        "same-position request must not be treated as applied change")
                .isEqualTo(
                        WorkforceMutationDecision.DENY);

        assertThat(
                currentPosition(
                        fixture.tenantId(),
                        fixture.targetStaffId()))
                .isEqualTo(
                        fixture.operationalPositionId());

        var audit =
                jdbcTemplate.queryForMap(
                        """
                        SELECT
                            action_type,
                            outcome,
                            reason_code,
                            before_position_id,
                            after_position_id
                        FROM workforce.audit_events
                        WHERE audit_event_id = ?
                        """,
                        auditEventId);

        assertThat(audit.get("action_type"))
                .isEqualTo(
                        "PRIVILEGED_MUTATION");

        assertThat(audit.get("outcome"))
                .isEqualTo(
                        "DENIED");

        assertThat(audit.get("reason_code"))
                .isEqualTo(
                        "POSITION_UNCHANGED");

        assertThat(audit.get("before_position_id"))
                .isEqualTo(
                        fixture.operationalPositionId());

        assertThat(audit.get("after_position_id"))
                .isEqualTo(
                        fixture.operationalPositionId());
    }

    @Test
    void concurrentEquivalentRequestsReevaluateLatestPlacementAfterDatabaseLock()
            throws Exception {

        var fixture = createFixture();

        var eventA = UUID.randomUUID();
        var eventB = UUID.randomUUID();

        var delegate =
                new PostgreSqlWorkforcePositionChangeRepository(
                        jdbcTemplate);

        var pausingRepository =
                new PausingPositionRepository(
                        delegate);

        var concurrentService =
                new PrivilegedPositionChangeExecutionService(
                        transactionExecutor,
                        pausingRepository,
                        new PostgreSqlWorkforceAuditRepository(
                                jdbcTemplate),
                        new PrivilegedWorkforceMutationAuthorizationService());

        var first =
                CompletableFuture.supplyAsync(() ->
                        concurrentService.execute(
                                command(
                                        fixture,
                                        fixture.managementPositionId(),
                                        eventA)));

        assertThat(
                pausingRepository.awaitFirstAuthoritativeLoad())
                .as(
                        "first privileged execution must hold authoritative PostgreSQL locks")
                .isTrue();

        var second =
                CompletableFuture.supplyAsync(() ->
                        concurrentService.execute(
                                command(
                                        fixture,
                                        fixture.managementPositionId(),
                                        eventB)));

        var secondWaitedForDatabaseLock =
                awaitPositionReadLockWait();

        pausingRepository.releaseFirstAuthoritativeLoad();

        var firstDecision =
                first.get(
                        10,
                        TimeUnit.SECONDS);

        var secondDecision =
                second.get(
                        10,
                        TimeUnit.SECONDS);

        assertThat(secondWaitedForDatabaseLock)
                .as(
                        "competing privileged execution must wait on PostgreSQL row locking")
                .isTrue();

        assertThat(firstDecision)
                .isEqualTo(
                        WorkforceMutationDecision.ALLOW);

        assertThat(secondDecision)
                .as(
                        "second equivalent request must re-evaluate the already changed placement")
                .isEqualTo(
                        WorkforceMutationDecision.DENY);

        assertThat(
                currentPosition(
                        fixture.tenantId(),
                        fixture.targetStaffId()))
                .isEqualTo(
                        fixture.managementPositionId());

        var firstAudit =
                jdbcTemplate.queryForMap(
                        """
                        SELECT
                            action_type,
                            outcome,
                            reason_code
                        FROM workforce.audit_events
                        WHERE audit_event_id = ?
                        """,
                        eventA);

        assertThat(firstAudit.get("action_type"))
                .isEqualTo(
                        "POSITION_AUTHORITY_CHANGED");

        assertThat(firstAudit.get("outcome"))
                .isEqualTo(
                        "APPLIED");

        assertThat(firstAudit.get("reason_code"))
                .isNull();

        var secondAudit =
                jdbcTemplate.queryForMap(
                        """
                        SELECT
                            action_type,
                            outcome,
                            reason_code
                        FROM workforce.audit_events
                        WHERE audit_event_id = ?
                        """,
                        eventB);

        assertThat(secondAudit.get("action_type"))
                .isEqualTo(
                        "PRIVILEGED_MUTATION");

        assertThat(secondAudit.get("outcome"))
                .isEqualTo(
                        "DENIED");

        assertThat(secondAudit.get("reason_code"))
                .isEqualTo(
                        "POSITION_UNCHANGED");
    }

    private boolean awaitPositionReadLockWait() {

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
                                FROM pg_stat_activity
                                WHERE datname = current_database()
                                  AND pid <> pg_backend_pid()
                                  AND wait_event_type = 'Lock'
                                  AND query LIKE
                                        '%workforce.staff_profiles%'
                            )
                            """,
                            Boolean.class);

            if (Boolean.TRUE.equals(waiting)) {
                return true;
            }

            try {
                Thread.sleep(
                        10);

            } catch (InterruptedException exception) {

                Thread.currentThread()
                        .interrupt();

                throw new IllegalStateException(
                        "Interrupted while observing PostgreSQL row-lock wait",
                        exception);
            }
        }

        return false;
    }

    private PrivilegedPositionChangeCommand command(
            Fixture fixture,
            UUID targetPositionId,
            UUID auditEventId) {

        return new PrivilegedPositionChangeCommand(
                fixture.actorStaffId(),
                fixture.targetStaffId(),
                fixture.tenantId(),
                targetPositionId,
                true,
                PermissionEnvelope.of(
                        Set.of(
                                PermissionCode.CATALOG_VIEW,
                                PermissionCode.CATALOG_MANAGE)),
                auditEventId,
                UUID.randomUUID());
    }

    private Fixture createFixture() {

        var tenantId = UUID.randomUUID();

        var departmentId = UUID.randomUUID();

        var actorStaffId = UUID.randomUUID();
        var targetStaffId = UUID.randomUUID();

        var governancePositionId = UUID.randomUUID();
        var operationalPositionId = UUID.randomUUID();
        var managementPositionId = UUID.randomUUID();

        insertDepartment(
                departmentId,
                tenantId);

        insertPosition(
                governancePositionId,
                tenantId,
                "GOV",
                "Tenant Governance",
                "TENANT_GOVERNANCE",
                PermissionCode.CATALOG_VIEW,
                PermissionCode.CATALOG_MANAGE);

        insertPosition(
                operationalPositionId,
                tenantId,
                "OPS",
                "Operations",
                "OPERATIONAL",
                PermissionCode.CATALOG_VIEW);

        insertPosition(
                managementPositionId,
                tenantId,
                "MGT",
                "Management",
                "MANAGEMENT",
                PermissionCode.CATALOG_VIEW,
                PermissionCode.CATALOG_MANAGE);

        insertActiveStaff(
                actorStaffId,
                tenantId);

        insertActiveStaff(
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
                operationalPositionId,
                managementPositionId);
    }

    private void insertDepartment(
            UUID departmentId,
            UUID tenantId) {

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

    private void insertPosition(
            UUID positionId,
            UUID tenantId,
            String code,
            String title,
            String authorityBand,
            PermissionCode... permissions) {

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

        for (var permission : permissions) {

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
                    permission.name());
        }
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

    private UUID currentPosition(
            UUID tenantId,
            UUID staffId) {

        return jdbcTemplate.queryForObject(
                """
                SELECT position_id
                FROM workforce.staff_placements
                WHERE tenant_id = ?
                  AND staff_id = ?
                """,
                UUID.class,
                tenantId,
                staffId);
    }

    private static final class PausingPositionRepository
            implements WorkforcePositionChangeRepository {

        private final WorkforcePositionChangeRepository delegate;

        private final AtomicBoolean pauseFirstLoad =
                new AtomicBoolean(
                        true);

        private final CountDownLatch firstLoadCompleted =
                new CountDownLatch(
                        1);

        private final CountDownLatch releaseFirstLoad =
                new CountDownLatch(
                        1);

        private PausingPositionRepository(
                WorkforcePositionChangeRepository delegate) {

            this.delegate = delegate;
        }

        @Override
        public WorkforcePositionChangeSnapshot loadForUpdate(
                UUID tenantId,
                UUID actorStaffId,
                UUID targetStaffId,
                UUID targetPositionId) {

            var snapshot =
                    delegate.loadForUpdate(
                            tenantId,
                            actorStaffId,
                            targetStaffId,
                            targetPositionId);

            if (pauseFirstLoad.compareAndSet(
                    true,
                    false)) {

                firstLoadCompleted.countDown();

                await(
                        releaseFirstLoad);
            }

            return snapshot;
        }

        @Override
        public void changePosition(
                UUID tenantId,
                UUID targetStaffId,
                UUID expectedCurrentPositionId,
                UUID targetPositionId) {

            delegate.changePosition(
                    tenantId,
                    targetStaffId,
                    expectedCurrentPositionId,
                    targetPositionId);
        }

        private boolean awaitFirstAuthoritativeLoad()
                throws InterruptedException {

            return firstLoadCompleted.await(
                    5,
                    TimeUnit.SECONDS);
        }

        private void releaseFirstAuthoritativeLoad() {

            releaseFirstLoad.countDown();
        }

        private static void await(
                CountDownLatch latch) {

            try {
                if (!latch.await(
                        10,
                        TimeUnit.SECONDS)) {

                    throw new IllegalStateException(
                            "Timed out waiting to release first authoritative load");
                }

            } catch (InterruptedException exception) {

                Thread.currentThread()
                        .interrupt();

                throw new IllegalStateException(
                        "Interrupted while pausing authoritative load",
                        exception);
            }
        }
    }

    private record Fixture(
            UUID tenantId,
            UUID actorStaffId,
            UUID targetStaffId,
            UUID operationalPositionId,
            UUID managementPositionId) {
    }
}
