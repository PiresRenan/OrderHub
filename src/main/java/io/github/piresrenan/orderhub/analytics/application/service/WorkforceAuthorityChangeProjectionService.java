package io.github.piresrenan.orderhub.analytics.application.service;

import java.util.Optional;
import java.util.UUID;

import io.github.piresrenan.orderhub.analytics.application.port.out.AnalyticalSubjectPseudonymRepository;
import io.github.piresrenan.orderhub.analytics.application.port.out.WorkforceAuthorityChangeFactRepository;
import io.github.piresrenan.orderhub.analytics.domain.model.WorkforceAuthorityChangeAction;
import io.github.piresrenan.orderhub.analytics.domain.model.WorkforceAuthorityChangeFact;
import io.github.piresrenan.orderhub.analytics.domain.model.WorkforceAuthorityChangeOutcome;
import io.github.piresrenan.orderhub.workforce.application.model.WorkforceAuditActionType;
import io.github.piresrenan.orderhub.workforce.application.model.WorkforceAuditOutcome;
import io.github.piresrenan.orderhub.workforce.application.port.in.ResolveWorkforceAuthorityChangeAnalyticsSourceUseCase;

/**
 * Projects one committed workforce authority-change audit event into a bounded
 * analytical fact.
 *
 * <p>
 * The service is addressed by Tenant scope and source-event identity only. It
 * reads the operational evidence back through the workforce-owned application
 * contract rather than receiving it in the notification, so the committed
 * operational row stays the single authority for what happened and the durable
 * transport keeps carrying two opaque identifiers.
 * </p>
 *
 * <p>
 * The service owns no transaction. It runs inside the boundary its caller
 * already established, so a projection failure rolls back every analytical
 * write it attempted, including a subject mapping first created here.
 * </p>
 */
public final class WorkforceAuthorityChangeProjectionService {

    private final ResolveWorkforceAuthorityChangeAnalyticsSourceUseCase
            sourceResolver;

    private final AnalyticalSubjectPseudonymRepository pseudonymRepository;

    private final WorkforceAuthorityChangeFactRepository factRepository;

    public WorkforceAuthorityChangeProjectionService(
            ResolveWorkforceAuthorityChangeAnalyticsSourceUseCase
                    sourceResolver,
            AnalyticalSubjectPseudonymRepository pseudonymRepository,
            WorkforceAuthorityChangeFactRepository factRepository) {

        if (sourceResolver == null) {
            throw new IllegalArgumentException(
                    "Workforce authority change analytics source resolver is"
                            + " required");
        }

        if (pseudonymRepository == null) {
            throw new IllegalArgumentException(
                    "Analytical subject pseudonym repository is required");
        }

        if (factRepository == null) {
            throw new IllegalArgumentException(
                    "Workforce authority change fact repository is required");
        }

        this.sourceResolver = sourceResolver;
        this.pseudonymRepository = pseudonymRepository;
        this.factRepository = factRepository;
    }

    /**
     * Projects the committed source behind one notification.
     *
     * <p>
     * An absent source is an invariant violation rather than a quiet no-op: a
     * notification is only published from inside the transaction that appended
     * the audit evidence, so a committed notification whose source cannot be
     * resolved for its own Tenant means something is wrong. Failing closed
     * keeps the publication recoverable instead of fabricating a fact.
     * </p>
     *
     * @param tenantId     Tenant scope carried by the notification
     * @param auditEventId operational audit event carried by the notification
     * @return whether a fact was produced or the action is unmodelled
     */
    public WorkforceAuthorityChangeProjectionResult project(
            UUID tenantId,
            UUID auditEventId) {

        if (tenantId == null) {
            throw new IllegalArgumentException(
                    "Tenant ID is required");
        }

        if (auditEventId == null) {
            throw new IllegalArgumentException(
                    "Audit event ID is required");
        }

        var source =
                sourceResolver.resolve(
                                tenantId,
                                auditEventId)
                        .orElseThrow(() ->
                                new WorkforceAuthorityChangeProjectionException(
                                        "Notified workforce authority-change"
                                                + " evidence is not resolvable"
                                                + " for its own Tenant"));

        var analyticalAction =
                analyticalAction(
                        source.action());

        if (analyticalAction.isEmpty()) {
            return WorkforceAuthorityChangeProjectionResult.IGNORED;
        }

        var actorSubject =
                pseudonymRepository.resolveOrCreate(
                        source.tenantId(),
                        source.actorStaffId());

        var affectedSubject =
                pseudonymRepository.resolveOrCreate(
                        source.tenantId(),
                        source.affectedStaffId());

        factRepository.append(
                new WorkforceAuthorityChangeFact(
                        source.auditEventId(),
                        source.tenantId(),
                        actorSubject,
                        affectedSubject,
                        analyticalAction.get(),
                        analyticalOutcome(
                                source.outcome()),
                        source.reasonCode(),
                        source.occurredAt()));

        return WorkforceAuthorityChangeProjectionResult.PROJECTED;
    }

    /**
     * Translates a workforce audit action into the analytics-owned vocabulary.
     *
     * <p>
     * The switch is exhaustive and has no default branch, so a new workforce
     * action becomes a compilation failure here rather than silently taking a
     * wrong analytical meaning at runtime.
     * </p>
     *
     * <p>
     * The empty results are deliberate. Those actions are not produced by any
     * current workforce workflow this projection analyses, and the analytical
     * fact contract intentionally does not model them. Inventing a vocabulary
     * for them would create permanent poison publications for evidence
     * analytics has no purpose for.
     * </p>
     */
    private static Optional<WorkforceAuthorityChangeAction> analyticalAction(
            WorkforceAuditActionType action) {

        return switch (action) {

            case POSITION_CHANGED ->
                Optional.of(
                        WorkforceAuthorityChangeAction.POSITION_CHANGED);

            case POSITION_AUTHORITY_CHANGED ->
                Optional.of(
                        WorkforceAuthorityChangeAction
                                .POSITION_AUTHORITY_CHANGED);

            case PRIVILEGED_MUTATION ->
                Optional.of(
                        WorkforceAuthorityChangeAction.PRIVILEGED_MUTATION);

            case STAFF_ACTIVATED,
                    STAFF_DEACTIVATED,
                    DEPARTMENT_CHANGED,
                    SUPERVISOR_CHANGED ->
                Optional.empty();
        };
    }

    /**
     * Translates a workforce audit outcome into the analytics-owned vocabulary.
     *
     * <p>
     * The mapping is explicit rather than name-based, so the two vocabularies
     * stay independently evolvable instead of becoming an implicit contract
     * between two modules' enum constants.
     * </p>
     */
    private static WorkforceAuthorityChangeOutcome analyticalOutcome(
            WorkforceAuditOutcome outcome) {

        return switch (outcome) {

            case APPLIED ->
                WorkforceAuthorityChangeOutcome.APPLIED;

            case DENIED ->
                WorkforceAuthorityChangeOutcome.DENIED;
        };
    }
}
