package io.github.piresrenan.orderhub.analytics.adapter.in.event.spring;

import java.util.concurrent.atomic.AtomicReference;

import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import io.micrometer.core.instrument.MeterRegistry;

import io.github.piresrenan.orderhub.analytics.application.service.WorkforceAuthorityChangeProjectionResult;
import io.github.piresrenan.orderhub.analytics.application.service.WorkforceAuthorityChangeProjectionService;
import io.github.piresrenan.orderhub.workforce.application.model.WorkforceAuthorityChangeAuditRecorded;

/**
 * Consumes the durable workforce authority-change notification and drives the
 * analytical projection.
 *
 * <p>
 * {@link ApplicationModuleListener} already supplies the selected semantics:
 * the listener runs after the source transaction commits, in a transaction of
 * its own. Adding the underlying annotations separately would restate the same
 * decision in a second place where the two could drift apart.
 * </p>
 *
 * <p>
 * Running after commit is what keeps a projection failure from deciding whether
 * an operational change happened. Running in its own transaction is what makes
 * a failed projection leave no partial analytical state behind.
 * </p>
 *
 * <p>
 * The listener identity is stable and deliberately unversioned. The durable
 * registry keys recovery state on it, so renaming it would orphan every
 * publication awaiting recovery under the old name.
 * </p>
 *
 * <p>
 * The class is deliberately not {@code final}. The transactional and
 * asynchronous semantics this listener depends on are applied by a generated
 * subclass, so sealing the type would silently disable the very behaviour the
 * annotation selects.
 * </p>
 */
public class WorkforceAuthorityChangeAuditRecordedListener {

    /**
     * Durable recovery identity of this publication target.
     */
    public static final String LISTENER_ID =
            "analytics-workforce-authority-change-projection";

    public static final String PROJECTION_METRIC =
            "orderhub.analytics.workforce.authority.change.projections";

    public static final String RESULT_TAG = "result";

    static final String PROJECTED_RESULT = "projected";

    static final String IGNORED_RESULT = "ignored";

    static final String FAILED_RESULT = "failed";

    private final WorkforceAuthorityChangeProjectionService projectionService;

    private final MeterRegistry meterRegistry;

    public WorkforceAuthorityChangeAuditRecordedListener(
            WorkforceAuthorityChangeProjectionService projectionService,
            MeterRegistry meterRegistry) {

        if (projectionService == null) {
            throw new IllegalArgumentException(
                    "Workforce authority change projection service is"
                            + " required");
        }

        if (meterRegistry == null) {
            throw new IllegalArgumentException(
                    "Meter registry is required");
        }

        this.projectionService = projectionService;
        this.meterRegistry = meterRegistry;
    }

    /**
     * Projects the committed source identified by the notification.
     *
     * <p>
     * A failure is counted and then rethrown unchanged. Swallowing it would
     * complete the publication and permanently lose the projection, so the
     * measurement deliberately observes the failure without altering it.
     * </p>
     *
     * <p>
     * The result is recorded when the listener transaction completes, not when
     * this method returns. Commit happens after the method returns, so counting
     * here would report a successful projection for a transaction that then
     * failed to commit and left the fact absent and the publication
     * outstanding. Deferring keeps the metric describing durable outcomes.
     * </p>
     */
    @ApplicationModuleListener(id = LISTENER_ID)
    public void onWorkforceAuthorityChangeAuditRecorded(
            WorkforceAuthorityChangeAuditRecorded notification) {

        var outcome =
                new AtomicReference<>(
                        FAILED_RESULT);

        var deferred =
                countWhenTransactionCompletes(
                        outcome);

        final WorkforceAuthorityChangeProjectionResult result;

        try {
            result =
                    projectionService.project(
                            notification.tenantId(),
                            notification.auditEventId());

        } catch (RuntimeException failure) {

            if (!deferred) {
                count(FAILED_RESULT);
            }

            throw failure;
        }

        outcome.set(
                result == WorkforceAuthorityChangeProjectionResult.PROJECTED
                        ? PROJECTED_RESULT
                        : IGNORED_RESULT);

        if (!deferred) {
            count(outcome.get());
        }
    }

    /**
     * Arranges for the outcome to be counted once the surrounding transaction
     * has actually resolved.
     *
     * <p>
     * A rollback for any reason, including a failure raised during commit
     * itself, is reported as a failed projection, because that is what the
     * durable state will show.
     * </p>
     *
     * @return whether counting was deferred; when no transaction is active the
     *         caller counts directly instead
     */
    private boolean countWhenTransactionCompletes(
            AtomicReference<String> outcome) {

        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return false;
        }

        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {

                    @Override
                    public void afterCompletion(
                            int status) {

                        count(
                                status == TransactionSynchronization
                                        .STATUS_COMMITTED
                                        ? outcome.get()
                                        : FAILED_RESULT);
                    }
                });

        return true;
    }

    /**
     * Records one bounded projection result.
     *
     * <p>
     * The only dimension is the closed result vocabulary. Tenant, audit event,
     * Staff, reason and correlation identifiers are deliberately absent,
     * because each would make the metric unbounded and turn observability into
     * a disclosure channel.
     * </p>
     */
    private void count(
            String result) {

        meterRegistry.counter(
                        PROJECTION_METRIC,
                        RESULT_TAG,
                        result)
                .increment();
    }
}
