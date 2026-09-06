package io.github.piresrenan.orderhub.workforce.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.test.context.ContextConfiguration;

import io.github.piresrenan.orderhub.OrderHubApplication;
import io.github.piresrenan.orderhub.authorization.domain.model.PermissionCode;
import io.github.piresrenan.orderhub.authorization.domain.model.PermissionEnvelope;
import io.github.piresrenan.orderhub.support.PostgreSqlTestConfiguration;
import io.github.piresrenan.orderhub.workforce.application.model.PrivilegedPositionChangeCommand;
import io.github.piresrenan.orderhub.workforce.application.model.WorkforceAuditActionType;
import io.github.piresrenan.orderhub.workforce.application.model.WorkforceAuditEvidence;
import io.github.piresrenan.orderhub.workforce.application.model.WorkforceAuditOutcome;
import io.github.piresrenan.orderhub.workforce.application.model.WorkforceAuditState;
import io.github.piresrenan.orderhub.workforce.application.port.out.WorkforceTransactionExecutor;
import io.github.piresrenan.orderhub.workforce.application.service.AuditedWorkforceMutationService;
import io.github.piresrenan.orderhub.workforce.application.service.PrivilegedPositionChangeExecutionService;
import io.github.piresrenan.orderhub.workforce.domain.model.StaffStatus;
import io.github.piresrenan.orderhub.workforce.domain.model.WorkforceMutationDecision;

/**
 * Proves that every privilege-significant workforce audit path registers one
 * minimal durable notification inside the transaction that already owns the
 * mutation and its audit evidence.
 *
 * <p>
 * The nested listener exists only so Spring Modulith has a real transactional
 * after-commit publication target. Without one, the registry correctly stores
 * nothing, and a test expecting a durable row would pass for the wrong reason.
 * It is test observability, not analytics behaviour, and it is deliberately not
 * production code.
 * </p>
 */
@SpringBootTest
@ContextConfiguration(
        classes = {
            OrderHubApplication.class,
            PostgreSqlTestConfiguration.class,
            WorkforceAuthorityChangePublicationTransactionTest
                    .TestPublicationTargetConfiguration.class
        })
class WorkforceAuthorityChangePublicationTransactionTest {

    /**
     * Stable identity of the test-only publication target, so a publication row
     * can be attributed without guessing a framework-derived listener name.
     */
    static final String TEST_LISTENER_ID =
            "oh016-test-workforce-authority-change-publication-target";

    /**
     * The notification workforce is expected to publish. It does not exist yet,
     * so it is referenced only as the value the registry records in
     * {@code event_type}; importing it would turn this into a compilation
     * failure and prove nothing about the runtime contract.
     */
    private static final String EXPECTED_NOTIFICATION_TYPE =
            "io.github.piresrenan.orderhub.workforce.application.model"
                    + ".WorkforceAuthorityChangeAuditRecorded";

    private static final String EXPECTED_PAYLOAD_KEYS =
            "auditEventId,tenantId";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private WorkforceTransactionExecutor transactionExecutor;

    @Autowired
    private AuditedWorkforceMutationService auditedMutationService;

    @Autowired
    private PrivilegedPositionChangeExecutionService privilegedPositionChangeService;

    @BeforeEach
    void cleanWorkforceAndPublicationState() {

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

        jdbcTemplate.execute(
                "TRUNCATE TABLE public.event_publication");

        TestPublicationTargetConfiguration.RECEIVED.clear();
    }

    @Test
    void auditedMutationRegistersMinimalPublicationInsideTheSourceTransaction() {
        // Why: a notification that were published after the workforce
        // transaction commits could be lost for an operation that did commit,
        // and one published outside that transaction could survive an operation
        // that rolled back. Registering it inside the source transaction is the
        // only arrangement where delivery intent and operational truth share a
        // fate.
        // Covers: the durable publication being visible inside the still-open
        // source transaction alongside the mutation and audit evidence, its
        // exact event type and minimal payload, and its disappearance when the
        // source transaction rolls back.
        // Prevents: publication after commit, publication outside the source
        // transaction, and analytical delivery intent surviving a rolled-back
        // operation.
        //
        // The audited mutation is invoked from inside an outer workforce
        // transaction, so its own REQUIRED execution joins that transaction and
        // the outer boundary decides the outcome.
        //
        // The transaction body only captures observations and then throws the
        // deliberate rollback, so no assertion inside it can decide the
        // transaction's fate. The rollback invariants are asserted before the
        // in-transaction publication contract, so a missing publication cannot
        // hide whether the rollback itself behaved correctly.

        var tenantId = UUID.randomUUID();
        var targetStaffId = UUID.randomUUID();
        var actorStaffId = UUID.randomUUID();
        var auditEventId = UUID.randomUUID();

        insertActiveStaff(
                targetStaffId,
                tenantId);

        var observed =
                new AtomicReference<InTransactionObservation>();

        assertThatThrownBy(() ->
                transactionExecutor.execute(() -> {

                    auditedMutationService.execute(
                            () -> deactivateStaff(
                                    targetStaffId,
                                    tenantId),
                            deactivationEvidence(
                                    auditEventId,
                                    tenantId,
                                    actorStaffId,
                                    targetStaffId));

                    observed.set(
                            new InTransactionObservation(
                                    staffStatus(
                                            targetStaffId,
                                            tenantId),
                                    auditCount(
                                            auditEventId),
                                    testTargetPublications()));

                    throw new DeliberateSourceRollback();
                }))
                .as("The source transaction must fail by the deliberate test"
                        + " rollback, not by anything else")
                .isInstanceOf(
                        DeliberateSourceRollback.class);

        var inTransaction =
                observed.get();

        assertThat(inTransaction)
                .as("The source transaction body must have completed its"
                        + " observations before rolling back")
                .isNotNull();

        assertThat(
                staffStatus(
                        targetStaffId,
                        tenantId))
                .as("The rolled-back mutation must not survive")
                .isEqualTo("ACTIVE");

        assertThat(
                auditCount(
                        auditEventId))
                .as("The rolled-back audit evidence must not survive")
                .isZero();

        assertThat(testTargetPublications())
                .as("Delivery intent must roll back with the operation it"
                        + " describes")
                .isEmpty();

        assertThat(
                notificationsFor(
                        auditEventId))
                .as("An after-commit target must never process the source event"
                        + " of a transaction that rolled back")
                .isZero();

        assertThat(inTransaction.staffStatus())
                .as("The mutation must be visible inside the source"
                        + " transaction")
                .isEqualTo("INACTIVE");

        assertThat(inTransaction.auditCount())
                .as("The audit evidence must be visible inside the source"
                        + " transaction")
                .isEqualTo(1);

        assertThat(inTransaction.publications())
                .as("The durable notification must already be registered inside"
                        + " the source transaction, before it resolves")
                .hasSize(1);

        assertPublicationContract(
                inTransaction.publications().get(0),
                tenantId,
                auditEventId);
    }

    @Test
    void committedAuditedMutationPublishesOnlyTheMinimalAuditReference() {
        // Why: the durable notification is stored in shared integration
        // infrastructure, so its payload is a privacy decision rather than an
        // implementation detail. Only opaque identity may be recorded there.
        // Covers: a committed audited mutation leaving exactly one publication
        // for the target, with the expected event type and a payload of exactly
        // tenantId and auditEventId.
        // Prevents: operational identity, organizational state, correlation
        // identity or free-form data reaching the publication log.
        //
        // Payload shape is asserted through PostgreSQL JSON functions rather
        // than by comparing a hand-built string, so property ordering is not
        // part of the contract. Exactly two top-level keys is itself the proof
        // that no further field was copied.

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
                .as("The committed mutation must survive")
                .isEqualTo("INACTIVE");

        assertThat(
                auditCount(
                        auditEventId))
                .as("The committed audit evidence must survive")
                .isEqualTo(1);

        var publications =
                testTargetPublications();

        assertThat(publications)
                .as("A committed audited mutation must leave exactly one"
                        + " durable notification for the target")
                .hasSize(1);

        assertPublicationContract(
                publications.get(0),
                tenantId,
                auditEventId);
    }

    @Test
    void deniedPrivilegedPositionChangePublishesTheSameMinimalAuditReference() {
        // Why: the specialised privileged-position workflow appends its own
        // audit evidence on denial, and a denied privilege-significant action
        // is still operational evidence an analytical projection retains as an
        // outcome. Leaving that path outside the notification contract would
        // silently exclude it from analytics.
        // Covers: a denied privileged position change producing the same
        // notification type and the same minimal payload as the generic audited
        // mutation path.
        // Prevents: the invariant holding only for the convenient service, and
        // denial reasons or authority state leaking into the publication log.

        var fixture =
                createFixture();

        var auditEventId = UUID.randomUUID();

        var decision =
                privilegedPositionChangeService.execute(
                        deniedCommand(
                                fixture,
                                auditEventId));

        assertThat(decision)
                .as("The privileged mutation must remain denied")
                .isEqualTo(
                        WorkforceMutationDecision.DENY);

        assertThat(
                currentPosition(
                        fixture.tenantId(),
                        fixture.targetStaffId()))
                .as("A denied privileged change must not move the placement")
                .isEqualTo(
                        fixture.operationalPositionId());

        assertThat(
                auditOutcome(
                        auditEventId))
                .as("The denied decision must be recorded as operational"
                        + " evidence")
                .isEqualTo("DENIED");

        var publications =
                testTargetPublications();

        assertThat(publications)
                .as("A denied privileged audit append must register the same"
                        + " durable notification as any other audit path")
                .hasSize(1);

        assertPublicationContract(
                publications.get(0),
                fixture.tenantId(),
                auditEventId);
    }

    /**
     * Asserts the frozen publication contract for one registry row.
     *
     * <p>
     * The two-key assertion is the privacy proof: no field can have been copied
     * into the payload without changing the key count.
     * </p>
     */
    private static void assertPublicationContract(
            Map<String, Object> publication,
            UUID expectedTenantId,
            UUID expectedAuditEventId) {

        assertThat(publication.get("listener_id"))
                .as("The publication must target the declared test listener")
                .isEqualTo(TEST_LISTENER_ID);

        assertThat(publication.get("event_type"))
                .as("The registry must record the workforce notification type")
                .isEqualTo(EXPECTED_NOTIFICATION_TYPE);

        assertThat(publication.get("payload_key_count"))
                .as("The durable payload must carry exactly two properties")
                .isEqualTo(2L);

        assertThat(publication.get("payload_keys"))
                .as("The durable payload must carry exactly the Tenant scope"
                        + " and the audit event identity")
                .isEqualTo(EXPECTED_PAYLOAD_KEYS);

        assertThat(publication.get("payload_tenant_id"))
                .as("The payload Tenant must be the source evidence Tenant")
                .isEqualTo(
                        expectedTenantId.toString());

        assertThat(publication.get("payload_audit_event_id"))
                .as("The payload must reference the exact operational audit"
                        + " event")
                .isEqualTo(
                        expectedAuditEventId.toString());
    }

    /**
     * Counts the notifications the test target processed for one source audit
     * event.
     *
     * <p>
     * Scoping by audit event rather than by a total invocation count keeps the
     * assertion independent of what any other test method published, so the
     * class needs no execution order.
     * </p>
     */
    private static long notificationsFor(
            UUID auditEventId) {

        return TestPublicationTargetConfiguration.RECEIVED.stream()
                .map(WorkforceAuthorityChangePublicationTransactionTest
                        ::publishedAuditEventId)
                .filter(auditEventId::equals)
                .count();
    }

    /**
     * Reads the audit event identity from a received notification through its
     * public record accessor.
     *
     * <p>
     * The notification type does not exist yet, so the accessor is reached
     * reflectively. Only the public record surface is used: no accessibility is
     * forced and no declared member is inspected. Nothing is reflected upon
     * until a notification has actually been received.
     * </p>
     */
    private static UUID publishedAuditEventId(
            Record notification) {

        try {
            return notification.getClass()
                    .getMethod("auditEventId")
                    .invoke(notification) instanceof UUID identity
                            ? identity
                            : null;

        } catch (ReflectiveOperationException unavailable) {
            return null;
        }
    }

    /**
     * Reads the publications registered for the test target.
     *
     * <p>
     * A list is returned rather than a single row so that the currently absent
     * publication surfaces as a cardinality assertion failure instead of a
     * data-access error.
     * </p>
     */
    private List<Map<String, Object>> testTargetPublications() {

        return jdbcTemplate.queryForList(
                """
                SELECT
                    listener_id,
                    event_type,
                    (
                        SELECT count(*)
                        FROM jsonb_object_keys(serialized_event::jsonb)
                    ) AS payload_key_count,
                    (
                        SELECT string_agg(payload_key, ',' ORDER BY payload_key)
                        FROM jsonb_object_keys(serialized_event::jsonb)
                                AS keys(payload_key)
                    ) AS payload_keys,
                    serialized_event::jsonb ->> 'tenantId'
                        AS payload_tenant_id,
                    serialized_event::jsonb ->> 'auditEventId'
                        AS payload_audit_event_id
                FROM public.event_publication
                WHERE listener_id = ?
                ORDER BY publication_date
                """,
                TEST_LISTENER_ID);
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

    private PrivilegedPositionChangeCommand deniedCommand(
            Fixture fixture,
            UUID auditEventId) {

        return new PrivilegedPositionChangeCommand(
                fixture.actorStaffId(),
                fixture.targetStaffId(),
                fixture.tenantId(),
                fixture.managementPositionId(),
                false,
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
                "TENANT-GOV",
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
                "MANAGEMENT",
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

    private int auditCount(
            UUID auditEventId) {

        var count =
                jdbcTemplate.queryForObject(
                        """
                        SELECT count(*)
                        FROM workforce.audit_events
                        WHERE audit_event_id = ?
                        """,
                        Integer.class,
                        auditEventId);

        return count == null ? 0 : count;
    }

    private String auditOutcome(
            UUID auditEventId) {

        var outcomes =
                jdbcTemplate.queryForList(
                        """
                        SELECT outcome
                        FROM workforce.audit_events
                        WHERE audit_event_id = ?
                        """,
                        String.class,
                        auditEventId);

        return outcomes.size() == 1
                ? outcomes.get(0)
                : null;
    }

    private record InTransactionObservation(
            String staffStatus,
            int auditCount,
            List<Map<String, Object>> publications) {
    }

    private record Fixture(
            UUID tenantId,
            UUID actorStaffId,
            UUID targetStaffId,
            UUID operationalPositionId,
            UUID managementPositionId) {
    }

    private static final class DeliberateSourceRollback
            extends RuntimeException {

        private static final long serialVersionUID = 1L;
    }

    private static final class DeliberateTargetFailure
            extends RuntimeException {

        private static final long serialVersionUID = 1L;
    }

    /**
     * Supplies the one transactional after-commit publication target this test
     * needs.
     *
     * <p>
     * Spring Modulith stores one publication per matching after-commit target,
     * so a source with no target would durably record nothing and the test
     * would pass without proving anything.
     * </p>
     *
     * <p>
     * The target accepts {@link Record} because the workforce notification does
     * not exist yet, and it fails deliberately because the configured DELETE
     * completion mode would otherwise remove the row before its payload can be
     * inspected. Neither choice is a statement about production failure
     * handling or recovery.
     * </p>
     *
     * <p>
     * Received notifications are retained so a test can ask whether its own
     * source event was processed, rather than reading a shared invocation
     * count that another test's asynchronous delivery could disturb.
     * </p>
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class TestPublicationTargetConfiguration {

        static final ConcurrentLinkedQueue<Record> RECEIVED =
                new ConcurrentLinkedQueue<>();

        @ApplicationModuleListener(id = TEST_LISTENER_ID)
        void onWorkforceNotification(
                Record notification) {

            RECEIVED.add(notification);

            throw new DeliberateTargetFailure();
        }
    }
}
