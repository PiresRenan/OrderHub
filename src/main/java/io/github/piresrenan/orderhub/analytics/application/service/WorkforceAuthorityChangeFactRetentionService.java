package io.github.piresrenan.orderhub.analytics.application.service;

import java.time.Instant;
import java.util.UUID;

import io.github.piresrenan.orderhub.analytics.application.port.out.WorkforceAuthorityChangeFactRetentionRepository;
import io.github.piresrenan.orderhub.analytics.domain.model.AnalyticalFactType;
import io.github.piresrenan.orderhub.analytics.domain.model.AnalyticalRetentionPolicyCatalog;

/**
 * Applies the effective retention policy to stored workforce authority-change
 * facts.
 *
 * <p>
 * The service owns policy lookup and cutoff derivation, so the persistence
 * boundary receives only an occurrence-time predicate and never a policy or a
 * clock.
 * </p>
 *
 * <p>
 * The retained fact type is fixed rather than supplied by callers. Current
 * analytical persistence is fact-specific, so a service that accepted an
 * arbitrary fact type would present a general contract backed by one specific
 * relation.
 * </p>
 *
 * <p>
 * Reference time is supplied by the caller, matching the analytical retention
 * model, which evaluates expiry against a caller-supplied instant rather than
 * reading a clock of its own. The service therefore selects no scheduling
 * behaviour and reads no database time.
 * </p>
 */
public final class WorkforceAuthorityChangeFactRetentionService {

    private static final AnalyticalFactType RETAINED_FACT_TYPE =
            AnalyticalFactType.WORKFORCE_AUTHORITY_CHANGE;

    private final AnalyticalRetentionPolicyCatalog retentionPolicyCatalog;

    private final WorkforceAuthorityChangeFactRetentionRepository
            retentionRepository;

    public WorkforceAuthorityChangeFactRetentionService(
            AnalyticalRetentionPolicyCatalog retentionPolicyCatalog,
            WorkforceAuthorityChangeFactRetentionRepository
                    retentionRepository) {

        if (retentionPolicyCatalog == null) {
            throw new IllegalArgumentException(
                    "Analytical retention policy catalog is required");
        }

        if (retentionRepository == null) {
            throw new IllegalArgumentException(
                    "Workforce authority change fact retention repository is"
                            + " required");
        }

        this.retentionPolicyCatalog = retentionPolicyCatalog;
        this.retentionRepository = retentionRepository;
    }

    /**
     * Removes the Tenant's workforce authority-change facts that have reached
     * expiry at the supplied reference time.
     *
     * <p>
     * A fact expires once its occurrence time plus the effective retention
     * window has been reached, so it is expired when
     * {@code occurredAt + retentionWindow <= referenceTime}. That is
     * equivalent to {@code occurredAt <= referenceTime - retentionWindow},
     * which is the inclusive cutoff handed to persistence. Expiry therefore
     * derives from operational occurrence time and never from ingestion or
     * database time, so a delayed or replayed ingestion cannot extend how long
     * analytical data is retained.
     * </p>
     *
     * @param tenantId      mandatory Tenant scope of the removal
     * @param referenceTime instant the retention policy is evaluated against
     * @return the number of facts this invocation removed
     */
    public int purgeExpired(
            UUID tenantId,
            Instant referenceTime) {

        if (tenantId == null) {
            throw new IllegalArgumentException(
                    "Tenant ID is required");
        }

        if (referenceTime == null) {
            throw new IllegalArgumentException(
                    "Reference time is required");
        }

        var policy =
                retentionPolicyCatalog.policyFor(
                        RETAINED_FACT_TYPE);

        var cutoff =
                referenceTime.minus(
                        policy.retentionWindow());

        return retentionRepository.deleteExpired(
                tenantId,
                cutoff);
    }
}
