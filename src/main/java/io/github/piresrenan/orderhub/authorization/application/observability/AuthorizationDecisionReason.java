package io.github.piresrenan.orderhub.authorization.application.observability;

/**
 * Bounded operational outcome vocabulary for authorization decision metrics.
 *
 * <p>
 * Values deliberately describe policy classes rather than Users, Tenants,
 * resources or external identity-provider values.
 * </p>
 */
public enum AuthorizationDecisionReason {

    ELIGIBLE,
    POLICY_DENIED,
    UNSUPPORTED_PERSONA,
    PERSISTENCE_FAILURE
}
