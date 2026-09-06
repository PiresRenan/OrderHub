package io.github.piresrenan.orderhub.analytics.application.port.out;

import io.github.piresrenan.orderhub.analytics.domain.model.WorkforceAuthorityChangeFact;

/**
 * Durable analytics-owned persistence boundary for workforce authority-change
 * facts.
 *
 * <p>
 * Implementations own no independent transaction boundary and participate in
 * the transaction established by the caller, if any.
 * </p>
 *
 * <p>
 * This boundary is only durable analytical fact storage. It selects no
 * workforce-to-analytics transport, no occurrence-time source, no commit-order
 * cursor and no retry, recovery or replay orchestration, and it authorizes no
 * producer.
 * </p>
 */
public interface WorkforceAuthorityChangeFactRepository {

    /**
     * Durably represents one bounded analytical fact.
     *
     * <p>
     * A fact is identified by its Tenant scope, its originating source event
     * and its analytical fact type. The schema version is persisted but is
     * deliberately not part of that identity.
     * </p>
     *
     * <p>
     * Returning normally means the exact semantic fact is durably represented,
     * whether this invocation stored it or an earlier one already had. The
     * first invocation for an identity may insert it, and an exact replay of
     * the same fact is an idempotent success rather than a failure, so an
     * at-least-once producer can retry safely.
     * </p>
     *
     * <p>
     * The same identity carrying divergent content is a conflict and fails.
     * Analytical facts are historical derivatives, so a persisted fact is never
     * overwritten, replaced or removed to accommodate a divergent candidate.
     * </p>
     *
     * @param fact bounded analytical fact to represent durably
     * @throws WorkforceAuthorityChangeFactPersistenceException when the
     *                                                          identity is
     *                                                          already
     *                                                          persisted with
     *                                                          conflicting
     *                                                          content, or
     *                                                          when persistence
     *                                                          otherwise fails
     */
    void append(WorkforceAuthorityChangeFact fact);
}
