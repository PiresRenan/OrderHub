package io.github.piresrenan.orderhub.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.modulith.events.FailedEventPublications;
import org.springframework.modulith.events.ResubmissionOptions;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import io.micrometer.core.instrument.MeterRegistry;

import io.github.piresrenan.orderhub.OrderHubApplication;
import io.github.piresrenan.orderhub.analytics.adapter.in.event.spring.WorkforceAuthorityChangeAuditRecordedListener;
import io.github.piresrenan.orderhub.analytics.adapter.out.persistence.postgresql.PostgreSqlWorkforceAuthorityChangeFactRepository;
import io.github.piresrenan.orderhub.analytics.application.port.out.WorkforceAuthorityChangeFactRepository;
import io.github.piresrenan.orderhub.analytics.application.service.WorkforceAuthorityChangeProjectionException;
import io.github.piresrenan.orderhub.analytics.application.service.WorkforceAuthorityChangeProjectionResult;
import io.github.piresrenan.orderhub.analytics.application.service.WorkforceAuthorityChangeProjectionService;
import io.github.piresrenan.orderhub.authorization.domain.model.PermissionCode;
import io.github.piresrenan.orderhub.authorization.domain.model.PermissionEnvelope;
import io.github.piresrenan.orderhub.support.PostgreSqlTestConfiguration;
import io.github.piresrenan.orderhub.workforce.application.model.PrivilegedPositionChangeCommand;
import io.github.piresrenan.orderhub.workforce.application.model.WorkforceAuditActionType;
import io.github.piresrenan.orderhub.workforce.application.model.WorkforceAuditEvidence;
import io.github.piresrenan.orderhub.workforce.application.model.WorkforceAuditOutcome;
import io.github.piresrenan.orderhub.workforce.application.model.WorkforceAuditState;
import io.github.piresrenan.orderhub.workforce.application.service.AuditedWorkforceMutationService;
import io.github.piresrenan.orderhub.workforce.application.service.PrivilegedPositionChangeExecutionService;
import io.github.piresrenan.orderhub.workforce.domain.model.StaffStatus;
import io.github.piresrenan.orderhub.workforce.domain.model.WorkforceMutationDecision;

/**
 * Proves the complete workforce-to-analytics ingestion path against real
 * infrastructure.
 *
 * <p>
 * Everything below the test is production: the workforce services, the durable
 * Spring Modulith JDBC publication registry, the real analytics listener, the
 * tenant-safe workforce source contract, analytics pseudonymization and the
 * analytical fact repository. The only test-owned component is a decorator that
 * can make analytical fact persistence fail on demand, which is the one
 * condition a real deployment cannot be asked to produce.
 * </p>
 *
 * <p>
 * The scenarios are kept in one class because they are one behaviour observed
 * at different points of the same pipeline, and because each needs the same
 * expensive real fixture.
 * </p>
 */
@SpringBootTest
@ContextConfiguration(
        classes = {
            OrderHubApplication.class,
            PostgreSqlTestConfiguration.class,
            WorkforceAuthorityChangeIngestionE2ETest
                    .FailableAnalyticalFactPersistenceConfiguration.class
        })
class WorkforceAuthorityChangeIngestionE2ETest {

    private static final Duration SETTLE_TIMEOUT =
            Duration.ofSeconds(30);

    private static final Duration POLL_INTERVAL =
            Duration.ofMillis(100);

    private static final String ANALYTICS_LISTENER_ID =
            WorkforceAuthorityChangeAuditRecordedListener.LISTENER_ID;

    private static final String EXPECTED_FACT_TYPE =
            "WORKFORCE_AUTHORITY_CHANGE";

    private static final int EXPECTED_SCHEMA_VERSION = 1;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private FailedEventPublications failedEventPublications;

    @Autowired
    private WorkforceAuthorityChangeProjectionService projectionService;

    @Autowired
    private PrivilegedPositionChangeExecutionService
            privilegedPositionChangeService;

    @Autowired
    private AuditedWorkforceMutationService auditedMutationService;

    @BeforeEach
    void resetOperationalAndAnalyticalState() {

        FailableAnalyticalFactPersistenceConfiguration.FAILING.set(false);

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
                "TRUNCATE TABLE analytics.workforce_authority_change_facts");

        jdbcTemplate.execute(
                "TRUNCATE TABLE analytics.subject_pseudonyms");

        jdbcTemplate.execute(
                "TRUNCATE TABLE public.event_publication");
    }

    @Test
    void appliedAuthorityChangeBecomesOnePseudonymousFactAfterSourceCommit() {
        // Why: this is the whole point of the ingestion path. An applied
        // privilege-significant change must reach analytics with its
        // operational meaning intact and its operational identities removed.
        // Covers: source commit, after-commit projection, tenant-safe source
        // resolution, pseudonymous subjects, exact vocabulary translation,
        // preserved operational occurrence time, fact type and schema version,
        // and DELETE completion of the successful publication.
        // Prevents: analytics inventing its own timestamp, persisting raw Staff
        // identifiers, mistranslating the action, and completed publications
        // accumulating in the registry.

        var fixture =
                createFixture();

        var auditEventId =
                UUID.randomUUID();

        var decision =
                privilegedPositionChangeService.execute(
                        appliedCommand(
                                fixture,
                                auditEventId));

        assertThat(decision)
                .as("The privileged change must be applied")
                .isEqualTo(
                        WorkforceMutationDecision.ALLOW);

        assertThat(
                currentPosition(
                        fixture.tenantId(),
                        fixture.targetStaffId()))
                .as("The operational placement must have moved")
                .isEqualTo(
                        fixture.managementPositionId());

        var fact =
                awaitSingleFact(
                        fixture.tenantId(),
                        auditEventId);

        assertThat(fact.get("source_event_id"))
                .as("The fact must be keyed by the operational audit event")
                .isEqualTo(auditEventId);

        assertThat(fact.get("tenant_id"))
                .as("The fact must carry its explicit Tenant scope")
                .isEqualTo(
                        fixture.tenantId());

        assertThat(fact.get("action"))
                .as("An authority-band change must translate to the analytical"
                        + " authority-change action")
                .isEqualTo(
                        "POSITION_AUTHORITY_CHANGED");

        assertThat(fact.get("outcome"))
                .isEqualTo("APPLIED");

        assertThat(fact.get("reason_code"))
                .as("An applied change carries no reason")
                .isNull();

        assertThat(fact.get("fact_type"))
                .isEqualTo(EXPECTED_FACT_TYPE);

        assertThat(fact.get("schema_version"))
                .isEqualTo(EXPECTED_SCHEMA_VERSION);

        assertThat(
                factOccurredAt(
                        fixture.tenantId(),
                        auditEventId))
                .as("The analytical occurrence time must be the one the"
                        + " operational audit row already persisted, never a"
                        + " projection or publication time")
                .isEqualTo(
                        auditOccurredAt(
                                auditEventId));

        assertPseudonymousSubjects(
                fact,
                fixture);

        awaitCompletedPublicationRemoval();
    }

    @Test
    void deniedPrivilegedChangeBecomesADeniedFactCarryingItsBoundedReason() {
        // Why: a denied privilege-significant action is operational evidence
        // analytics retains as an outcome. Excluding denials would silently
        // bias every analytical view toward successful changes only.
        // Covers: the denial not moving the placement, the DENIED audit
        // committing, the projection preserving outcome and bounded reason, and
        // the publication completing.
        // Prevents: denials being dropped, and denial reasons being replaced or
        // synthesized by analytics.

        var fixture =
                createFixture();

        var auditEventId =
                UUID.randomUUID();

        var decision =
                privilegedPositionChangeService.execute(
                        deniedCommand(
                                fixture,
                                auditEventId));

        assertThat(decision)
                .isEqualTo(
                        WorkforceMutationDecision.DENY);

        assertThat(
                currentPosition(
                        fixture.tenantId(),
                        fixture.targetStaffId()))
                .as("A denied change must not move the placement")
                .isEqualTo(
                        fixture.operationalPositionId());

        var fact =
                awaitSingleFact(
                        fixture.tenantId(),
                        auditEventId);

        assertThat(fact.get("action"))
                .isEqualTo("PRIVILEGED_MUTATION");

        assertThat(fact.get("outcome"))
                .isEqualTo("DENIED");

        assertThat(fact.get("reason_code"))
                .as("The bounded operational reason must be preserved exactly")
                .isEqualTo("PRIVILEGED_POLICY_DENIED");

        assertThat(
                factOccurredAt(
                        fixture.tenantId(),
                        auditEventId))
                .isEqualTo(
                        auditOccurredAt(
                                auditEventId));

        assertPseudonymousSubjects(
                fact,
                fixture);

        awaitCompletedPublicationRemoval();
    }

    @Test
    void sourceActionOutsideTheAnalyticalVocabularyCompletesWithoutAFact() {
        // Why: workforce owns a broader audit vocabulary than analytics models.
        // Treating an unmodelled action as a failure would create a publication
        // that can never succeed, and inventing an analytical action for it
        // would put a meaning in the fact contract that no analytical purpose
        // asked for.
        // Covers: a committed audit event whose action analytics deliberately
        // does not model completing the publication and producing no fact.
        // Prevents: poison publications and speculative vocabulary growth.

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
                auditCount(
                        auditEventId))
                .as("The operational audit evidence must have committed")
                .isEqualTo(1);

        awaitCompletedPublicationRemoval();

        assertThat(
                factCount(
                        tenantId,
                        auditEventId))
                .as("An unmodelled source action must produce no analytical"
                        + " fact")
                .isZero();

        assertThat(
                pseudonymCount(
                        tenantId))
                .as("An unmodelled action needs no analytical subject identity,"
                        + " so none may be established")
                .isZero();
    }

    @Test
    void projectionFailureIsolatesTheSourceAndStaysRecoverableUntilItConverges() {
        // Why: once the source transaction commits, analytics failure must not
        // be able to undo it, and the projection must not be lost either. Those
        // two requirements together are the entire justification for a durable
        // publication registry rather than a best-effort event.
        // Covers: a committed source surviving analytical persistence failure,
        // the failed listener transaction leaving no partial analytical state,
        // the publication being retained as FAILED, explicit framework
        // resubmission producing the fact, DELETE completion removing the row,
        // and a further replay converging on the same single fact.
        // Prevents: source rollback caused by analytics, silent loss of a
        // projection, partial analytical state, and duplicate facts under
        // at-least-once delivery.
        //
        // Failure is injected at the analytical persistence boundary rather
        // than by replacing the listener or the registry, so every transaction
        // boundary under test remains the production one.

        FailableAnalyticalFactPersistenceConfiguration.FAILING.set(true);

        var fixture =
                createFixture();

        var auditEventId =
                UUID.randomUUID();

        var decision =
                privilegedPositionChangeService.execute(
                        appliedCommand(
                                fixture,
                                auditEventId));

        assertThat(decision)
                .as("The operational caller must be unaffected by a projection"
                        + " that fails after its transaction committed")
                .isEqualTo(
                        WorkforceMutationDecision.ALLOW);

        assertThat(
                currentPosition(
                        fixture.tenantId(),
                        fixture.targetStaffId()))
                .as("The committed operational change must survive analytical"
                        + " failure")
                .isEqualTo(
                        fixture.managementPositionId());

        assertThat(
                auditCount(
                        auditEventId))
                .as("The committed audit evidence must survive analytical"
                        + " failure")
                .isEqualTo(1);

        await().atMost(SETTLE_TIMEOUT)
                .pollInterval(POLL_INTERVAL)
                .untilAsserted(() ->
                        assertThat(
                                analyticsPublicationStatus())
                                .as("A failed projection must leave its"
                                        + " publication retained and"
                                        + " recoverable")
                                .isEqualTo("FAILED"));

        assertThat(
                factCount(
                        fixture.tenantId(),
                        auditEventId))
                .as("The failed listener transaction must leave no fact")
                .isZero();

        assertThat(
                pseudonymCount(
                        fixture.tenantId()))
                .as("Subject identity created inside the failed listener"
                        + " transaction must roll back with it")
                .isZero();

        FailableAnalyticalFactPersistenceConfiguration.FAILING.set(false);

        failedEventPublications.resubmit(
                ResubmissionOptions.defaults()
                        .withMinAge(
                                Duration.ZERO));

        var recovered =
                awaitSingleFact(
                        fixture.tenantId(),
                        auditEventId);

        assertThat(recovered.get("action"))
                .as("Recovery must produce the same semantic fact the first"
                        + " attempt owed")
                .isEqualTo("POSITION_AUTHORITY_CHANGED");

        assertThat(recovered.get("outcome"))
                .isEqualTo("APPLIED");

        assertThat(
                factOccurredAt(
                        fixture.tenantId(),
                        auditEventId))
                .as("Recovery must not substitute the retry time for the"
                        + " operational occurrence time")
                .isEqualTo(
                        auditOccurredAt(
                                auditEventId));

        assertPseudonymousSubjects(
                recovered,
                fixture);

        awaitCompletedPublicationRemoval();

        var replayResult =
                new TransactionTemplate(
                        transactionManager)
                        .execute(status ->
                                projectionService.project(
                                        fixture.tenantId(),
                                        auditEventId));

        assertThat(replayResult)
                .as("An exact replay of a delivered notification must be an"
                        + " idempotent success, because delivery is"
                        + " at-least-once")
                .isEqualTo(
                        WorkforceAuthorityChangeProjectionResult.PROJECTED);

        assertThat(
                factCount(
                        fixture.tenantId(),
                        auditEventId))
                .as("Replay must converge on one fact rather than accumulate"
                        + " duplicates")
                .isEqualTo(1);
    }

    @Test
    void unresolvableSourceFailsClosedRatherThanInventingAnalyticalState() {
        // Why: a notification is only published from inside the transaction
        // that appended the audit evidence, so a notification whose source
        // cannot be resolved for its own Tenant means an invariant is broken.
        // Covers: the projection service refusing to proceed without committed
        // operational evidence.
        // Prevents: a fabricated fact, and a failure being converted into a
        // silent success that would complete the publication and destroy the
        // only chance of recovery.

        var tenantId = UUID.randomUUID();
        var auditEventId = UUID.randomUUID();

        assertThatThrownBy(() ->
                projectionService.project(
                        tenantId,
                        auditEventId))
                .isInstanceOf(
                        WorkforceAuthorityChangeProjectionException.class);

        assertThat(
                factCount(
                        tenantId,
                        auditEventId))
                .isZero();

        assertThat(
                pseudonymCount(
                        tenantId))
                .isZero();
    }

    @Test
    void projectionObservabilityCarriesOnlyABoundedResultDimension() {
        // Why: analytical observability describes a Tenant-scoped, subject-
        // level pipeline. A single identifier used as a metric label would make
        // cardinality unbounded and turn metrics into a disclosure channel.
        // Covers: the projection counter existing after a real projection and
        // carrying exactly one bounded dimension.
        // Prevents: Tenant, audit event, Staff, reason or correlation
        // identifiers reaching the metric registry.

        var fixture =
                createFixture();

        var auditEventId =
                UUID.randomUUID();

        privilegedPositionChangeService.execute(
                appliedCommand(
                        fixture,
                        auditEventId));

        awaitSingleFact(
                fixture.tenantId(),
                auditEventId);

        var counters =
                meterRegistry.find(
                                WorkforceAuthorityChangeAuditRecordedListener
                                        .PROJECTION_METRIC)
                        .counters();

        assertThat(counters)
                .as("A real projection must be measured")
                .isNotEmpty();

        for (var counter : counters) {

            var tags =
                    counter.getId()
                            .getTags();

            assertThat(
                    tags.stream()
                            .map(io.micrometer.core.instrument.Tag::getKey)
                            .toList())
                    .as("The projection metric must carry exactly one bounded"
                            + " dimension")
                    .containsExactly(
                            WorkforceAuthorityChangeAuditRecordedListener
                                    .RESULT_TAG);

            assertThat(
                    counter.getId()
                            .getTag(
                                    WorkforceAuthorityChangeAuditRecordedListener
                                            .RESULT_TAG))
                    .as("The result dimension must stay a closed vocabulary")
                    .isIn(
                            "projected",
                            "ignored",
                            "failed");
        }
    }

    /**
     * Asserts that the fact identifies its subjects only through analytical
     * keys, and that those keys are the ones the analytics-owned mapping holds.
     */
    private void assertPseudonymousSubjects(
            Map<String, Object> fact,
            Fixture fixture) {

        var actorSubject =
                fact.get("actor_subject_key");

        var affectedSubject =
                fact.get("affected_subject_key");

        assertThat(actorSubject)
                .as("The actor must be represented pseudonymously")
                .isNotNull()
                .isNotEqualTo(
                        fixture.actorStaffId());

        assertThat(affectedSubject)
                .as("The affected subject must be represented pseudonymously")
                .isNotNull()
                .isNotEqualTo(
                        fixture.targetStaffId());

        assertThat(
                List.of(
                        actorSubject,
                        affectedSubject))
                .as("No operational Staff identifier may appear in any fact"
                        + " subject column")
                .doesNotContain(
                        fixture.actorStaffId(),
                        fixture.targetStaffId());

        assertThat(
                analyticalSubjectKey(
                        fixture.tenantId(),
                        fixture.actorStaffId()))
                .as("The persisted subject key must be the one the"
                        + " analytics-owned mapping resolved")
                .isEqualTo(actorSubject);

        assertThat(
                analyticalSubjectKey(
                        fixture.tenantId(),
                        fixture.targetStaffId()))
                .isEqualTo(affectedSubject);
    }

    private Map<String, Object> awaitSingleFact(
            UUID tenantId,
            UUID auditEventId) {

        await().atMost(SETTLE_TIMEOUT)
                .pollInterval(POLL_INTERVAL)
                .untilAsserted(() ->
                        assertThat(
                                factCount(
                                        tenantId,
                                        auditEventId))
                                .as("The committed source must reach analytics"
                                        + " as exactly one fact")
                                .isEqualTo(1));

        return jdbcTemplate.queryForMap(
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
                    reason_code
                FROM analytics.workforce_authority_change_facts
                WHERE tenant_id = ?
                  AND source_event_id = ?
                """,
                tenantId,
                auditEventId);
    }

    private void awaitCompletedPublicationRemoval() {

        await().atMost(SETTLE_TIMEOUT)
                .pollInterval(POLL_INTERVAL)
                .untilAsserted(() ->
                        assertThat(
                                analyticsPublicationCount())
                                .as("DELETE completion must remove a"
                                        + " successfully processed publication"
                                        + " instead of retaining it")
                                .isZero());
    }

    private String analyticsPublicationStatus() {

        var statuses =
                jdbcTemplate.queryForList(
                        """
                        SELECT status
                        FROM public.event_publication
                        WHERE listener_id = ?
                        """,
                        String.class,
                        ANALYTICS_LISTENER_ID);

        return statuses.size() == 1
                ? statuses.get(0)
                : null;
    }

    private int analyticsPublicationCount() {

        return count(
                """
                SELECT count(*)
                FROM public.event_publication
                WHERE listener_id = ?
                """,
                ANALYTICS_LISTENER_ID);
    }

    private Instant factOccurredAt(
            UUID tenantId,
            UUID auditEventId) {

        return instant(
                """
                SELECT occurred_at
                FROM analytics.workforce_authority_change_facts
                WHERE tenant_id = ?
                  AND source_event_id = ?
                """,
                tenantId,
                auditEventId);
    }

    private Instant auditOccurredAt(
            UUID auditEventId) {

        return instant(
                """
                SELECT occurred_at
                FROM workforce.audit_events
                WHERE audit_event_id = ?
                """,
                auditEventId);
    }

    private Instant instant(
            String sql,
            Object... arguments) {

        var value =
                jdbcTemplate.queryForObject(
                        sql,
                        Timestamp.class,
                        arguments);

        return value == null ? null : value.toInstant();
    }

    private UUID analyticalSubjectKey(
            UUID tenantId,
            UUID operationalSubjectId) {

        var keys =
                jdbcTemplate.queryForList(
                        """
                        SELECT analytical_subject_key
                        FROM analytics.subject_pseudonyms
                        WHERE tenant_id = ?
                          AND operational_subject_id = ?
                        """,
                        UUID.class,
                        tenantId,
                        operationalSubjectId);

        return keys.size() == 1
                ? keys.get(0)
                : null;
    }

    private int factCount(
            UUID tenantId,
            UUID auditEventId) {

        return count(
                """
                SELECT count(*)
                FROM analytics.workforce_authority_change_facts
                WHERE tenant_id = ?
                  AND source_event_id = ?
                """,
                tenantId,
                auditEventId);
    }

    private int pseudonymCount(
            UUID tenantId) {

        return count(
                """
                SELECT count(*)
                FROM analytics.subject_pseudonyms
                WHERE tenant_id = ?
                """,
                tenantId);
    }

    private int auditCount(
            UUID auditEventId) {

        return count(
                """
                SELECT count(*)
                FROM workforce.audit_events
                WHERE audit_event_id = ?
                """,
                auditEventId);
    }

    private int count(
            String sql,
            Object... arguments) {

        var value =
                jdbcTemplate.queryForObject(
                        sql,
                        Integer.class,
                        arguments);

        return value == null ? 0 : value;
    }

    private PrivilegedPositionChangeCommand appliedCommand(
            Fixture fixture,
            UUID auditEventId) {

        return new PrivilegedPositionChangeCommand(
                fixture.actorStaffId(),
                fixture.targetStaffId(),
                fixture.tenantId(),
                fixture.managementPositionId(),
                true,
                PermissionEnvelope.of(
                        Set.of(
                                PermissionCode.CATALOG_VIEW,
                                PermissionCode.CATALOG_MANAGE)),
                auditEventId,
                UUID.randomUUID());
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

    private record Fixture(
            UUID tenantId,
            UUID actorStaffId,
            UUID targetStaffId,
            UUID operationalPositionId,
            UUID managementPositionId) {
    }

    /**
     * Makes analytical fact persistence fail on demand.
     *
     * <p>
     * The decorator wraps the real PostgreSQL repository and delegates whenever
     * failure is disabled, so every scenario that is not exercising failure
     * still runs against real persistence. It exists only in this test, adds no
     * production behaviour, and replaces neither the listener, the registry nor
     * the workforce source contract.
     * </p>
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class FailableAnalyticalFactPersistenceConfiguration {

        static final AtomicBoolean FAILING =
                new AtomicBoolean();

        @Bean
        @Primary
        WorkforceAuthorityChangeFactRepository
                failableWorkforceAuthorityChangeFactRepository(
                        JdbcTemplate jdbcTemplate) {

            var delegate =
                    new PostgreSqlWorkforceAuthorityChangeFactRepository(
                            jdbcTemplate);

            return fact -> {

                if (FAILING.get()) {
                    throw new DeliberateAnalyticalPersistenceFailure();
                }

                delegate.append(fact);
            };
        }
    }

    private static final class DeliberateAnalyticalPersistenceFailure
            extends RuntimeException {

        private static final long serialVersionUID = 1L;
    }
}
