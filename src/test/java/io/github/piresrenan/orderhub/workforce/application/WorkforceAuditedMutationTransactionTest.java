package io.github.piresrenan.orderhub.workforce.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;

import io.github.piresrenan.orderhub.OrderHubApplication;
import io.github.piresrenan.orderhub.workforce.adapter.out.persistence.postgresql.PostgreSqlWorkforceAuditRepository;
import io.github.piresrenan.orderhub.workforce.adapter.out.transaction.spring.SpringWorkforceTransactionExecutor;
import io.github.piresrenan.orderhub.workforce.application.model.WorkforceAuditActionType;
import io.github.piresrenan.orderhub.workforce.application.model.WorkforceAuditEvidence;
import io.github.piresrenan.orderhub.workforce.application.model.WorkforceAuditOutcome;
import io.github.piresrenan.orderhub.workforce.application.model.WorkforceAuditState;
import io.github.piresrenan.orderhub.workforce.application.port.out.WorkforceAuditPersistenceException;
import io.github.piresrenan.orderhub.workforce.application.port.out.WorkforceAuditRepository;
import io.github.piresrenan.orderhub.workforce.application.port.out.WorkforceTransactionExecutor;
import io.github.piresrenan.orderhub.workforce.application.service.AuditedWorkforceMutationService;
import io.github.piresrenan.orderhub.workforce.domain.model.StaffStatus;
import io.github.piresrenan.orderhub.support.PostgreSqlTestConfiguration;

@SpringBootTest
@ContextConfiguration(
        classes = {
            OrderHubApplication.class,
            PostgreSqlTestConfiguration.class
        })
class WorkforceAuditedMutationTransactionTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private WorkforceTransactionExecutor transactionExecutor;

    @Autowired
    private WorkforceAuditRepository auditRepository;

    @Autowired
    private AuditedWorkforceMutationService auditedMutationService;

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
    void applicationContextWiresWorkforceOwnedTransactionAndAuditAdapters() {

        assertThat(transactionExecutor)
                .isInstanceOf(
                        SpringWorkforceTransactionExecutor.class);

        assertThat(auditRepository)
                .isInstanceOf(
                        PostgreSqlWorkforceAuditRepository.class);

        assertThat(auditedMutationService)
                .isNotNull();
    }

    @Test
    void serviceExecutesMutationBeforeAuditInsideOneExecutorInvocation() {

        var executions = new AtomicInteger();
        var order = new ArrayList<String>();

        WorkforceTransactionExecutor executor =
                new WorkforceTransactionExecutor() {
                    @Override
                    public <T> T execute(
                            java.util.function.Supplier<T> work) {

                        executions.incrementAndGet();
                        return work.get();
                    }
                };

        WorkforceAuditRepository repository =
                evidence -> order.add("audit");

        var service =
                new AuditedWorkforceMutationService(
                        executor,
                        repository);

        service.execute(
                () -> order.add("mutation"),
                sampleEvidence(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        UUID.randomUUID()));

        assertThat(executions.get())
                .isEqualTo(1);

        assertThat(order)
                .containsExactly(
                        "mutation",
                        "audit");
    }

    @Test
    void committedMutationAndAuditEvidenceCommitTogether() {

        var tenantId = UUID.randomUUID();
        var targetStaffId = UUID.randomUUID();
        var actorStaffId = UUID.randomUUID();
        var auditEventId = UUID.randomUUID();

        insertActiveStaff(
                targetStaffId,
                tenantId);

        auditedMutationService.execute(
                () -> deactivateStaff(
                        targetStaffId,
                        tenantId),
                deactivationEvidence(
                        auditEventId,
                        tenantId,
                        actorStaffId,
                        targetStaffId));

        assertThat(
                staffStatus(
                        targetStaffId,
                        tenantId))
                .isEqualTo("INACTIVE");

        assertThat(
                auditCount(
                        auditEventId))
                .isEqualTo(1);
    }

    @Test
    void auditFailureRollsBackTheBusinessMutation() {

        var tenantId = UUID.randomUUID();
        var targetStaffId = UUID.randomUUID();
        var actorStaffId = UUID.randomUUID();
        var auditEventId = UUID.randomUUID();

        insertActiveStaff(
                targetStaffId,
                tenantId);

        var evidence =
                deactivationEvidence(
                        auditEventId,
                        tenantId,
                        actorStaffId,
                        targetStaffId);

        auditRepository.append(
                evidence);

        assertThatThrownBy(() ->
                auditedMutationService.execute(
                        () -> deactivateStaff(
                                targetStaffId,
                                tenantId),
                        evidence))
                .isInstanceOf(
                        WorkforceAuditPersistenceException.class);

        assertThat(
                staffStatus(
                        targetStaffId,
                        tenantId))
                .isEqualTo("ACTIVE");

        assertThat(
                auditCount(
                        auditEventId))
                .isEqualTo(1);
    }

    @Test
    void mutationFailureRollsBackMutationAndPreventsAuditAppend() {

        var tenantId = UUID.randomUUID();
        var targetStaffId = UUID.randomUUID();
        var actorStaffId = UUID.randomUUID();
        var auditEventId = UUID.randomUUID();

        insertActiveStaff(
                targetStaffId,
                tenantId);

        var evidence =
                deactivationEvidence(
                        auditEventId,
                        tenantId,
                        actorStaffId,
                        targetStaffId);

        assertThatThrownBy(() ->
                auditedMutationService.execute(
                        () -> {
                            deactivateStaff(
                                    targetStaffId,
                                    tenantId);

                            throw new ExpectedMutationFailure();
                        },
                        evidence))
                .isInstanceOf(
                        ExpectedMutationFailure.class);

        assertThat(
                staffStatus(
                        targetStaffId,
                        tenantId))
                .isEqualTo("ACTIVE");

        assertThat(
                auditCount(
                        auditEventId))
                .isZero();
    }

    private WorkforceAuditEvidence deactivationEvidence(
            UUID auditEventId,
            UUID tenantId,
            UUID actorStaffId,
            UUID targetStaffId) {

        return new WorkforceAuditEvidence(
                auditEventId,
                tenantId,
                actorStaffId,
                targetStaffId,
                WorkforceAuditActionType.STAFF_DEACTIVATED,
                WorkforceAuditOutcome.APPLIED,
                null,
                UUID.randomUUID(),
                new WorkforceAuditState(
                        StaffStatus.ACTIVE,
                        null,
                        null,
                        null,
                        null),
                new WorkforceAuditState(
                        StaffStatus.INACTIVE,
                        null,
                        null,
                        null,
                        null));
    }

    private WorkforceAuditEvidence sampleEvidence(
            UUID auditEventId,
            UUID tenantId,
            UUID targetStaffId) {

        return new WorkforceAuditEvidence(
                auditEventId,
                tenantId,
                UUID.randomUUID(),
                targetStaffId,
                WorkforceAuditActionType.PRIVILEGED_MUTATION,
                WorkforceAuditOutcome.DENIED,
                "INSUFFICIENT_AUTHORITY_BAND",
                UUID.randomUUID(),
                WorkforceAuditState.empty(),
                WorkforceAuditState.empty());
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

    private int auditCount(
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

    private static final class ExpectedMutationFailure
            extends RuntimeException {

        private static final long serialVersionUID = 1L;
    }
}
