package io.github.piresrenan.orderhub.authorization.application.port.out;

import io.github.piresrenan.orderhub.authorization.application.observability.AuthorizationDecisionObservation;

/**
 * Operational observation boundary for authorization decisions.
 *
 * <p>
 * Observation is explicitly secondary to authorization correctness. Adapter
 * failure must never convert DENY to ALLOW or ALLOW to DENY.
 * </p>
 */
@FunctionalInterface
public interface AuthorizationDecisionObserver {

    void observe(
            AuthorizationDecisionObservation observation);
}
