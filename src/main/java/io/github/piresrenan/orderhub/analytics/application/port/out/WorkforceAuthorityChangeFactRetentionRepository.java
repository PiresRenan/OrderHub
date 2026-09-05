package io.github.piresrenan.orderhub.analytics.application.port.out;

import java.time.Instant;
import java.util.UUID;

/**
 * Analytics-owned retention boundary for workforce authority-change facts.
 *
 * <p>
 * The boundary is deliberately separate from the append boundary. Appending a
 * fact never removes one, while retention removes analytics-owned derivatives
 * that have reached the end of their configured lifetime. Keeping the two
 * boundaries apart stops fact storage from acquiring destructive semantics.
 * </p>
 *
 * <p>
 * Implementations own no independent transaction boundary and participate in
 * the transaction established by the caller, if any.
 * </p>
 *
 * <p>
 * Retention operates only on analytics-owned fact storage. It never removes or
 * mutates the identifying subject mapping, workforce audit evidence or any
 * other module-owned state.
 * </p>
 */
public interface WorkforceAuthorityChangeFactRetentionRepository {

    /**
     * Removes the Tenant's workforce authority-change facts that occurred at or
     * before the supplied cutoff.
     *
     * <p>
     * The cutoff is an already-derived occurrence-time boundary rather than a
     * policy or a reference clock: the application layer owns retention policy
     * lookup and cutoff derivation, so this boundary receives only the
     * persistence predicate. The comparison is inclusive, because a fact whose
     * lifetime ends exactly at the cutoff has reached expiry.
     * </p>
     *
     * @param tenantId           mandatory Tenant scope of the removal
     * @param occurredAtOrBefore inclusive occurrence-time cutoff
     * @return the number of facts this invocation removed
     * @throws WorkforceAuthorityChangeFactPersistenceException when removal
     *                                                          fails
     */
    int deleteExpired(
            UUID tenantId,
            Instant occurredAtOrBefore);
}
