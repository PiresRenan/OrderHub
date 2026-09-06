package io.github.piresrenan.orderhub.analytics.application.service;

/**
 * Outcome of projecting one notified workforce authority-change event.
 *
 * <p>
 * A successful projection and a deliberate non-projection are distinct results
 * rather than a boolean, because they mean different things operationally: one
 * produced an analytical fact, the other established that the current
 * analytical contract does not model the source action at all.
 * </p>
 *
 * <p>
 * Both are successes. Failure is signalled by an exception, so a caller can
 * never mistake a failure for a decision not to project.
 * </p>
 */
public enum WorkforceAuthorityChangeProjectionResult {

    /**
     * The source action is modelled analytically and its fact is durable.
     */
    PROJECTED,

    /**
     * The source action is outside the current analytical vocabulary, so no
     * fact was produced and none is owed.
     */
    IGNORED
}
