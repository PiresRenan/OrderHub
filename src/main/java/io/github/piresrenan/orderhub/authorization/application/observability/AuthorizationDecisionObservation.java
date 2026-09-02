package io.github.piresrenan.orderhub.authorization.application.observability;

import io.github.piresrenan.orderhub.authorization.domain.model.AuthorizationDecision;
import io.github.piresrenan.orderhub.authorization.domain.model.AuthorizationPersona;
import io.github.piresrenan.orderhub.authorization.domain.model.PermissionCode;

/**
 * Privacy-safe bounded observation of one authorization decision.
 *
 * <p>
 * This type intentionally cannot carry User IDs, Tenant IDs, resource IDs,
 * issuer/subject pairs, JWT claims or arbitrary strings.
 * </p>
 */
public record AuthorizationDecisionObservation(
        AuthorizationDecision decision,
        AuthorizationPersona persona,
        PermissionCode permission,
        AuthorizationDecisionReason reason) {

    public AuthorizationDecisionObservation {

        if (decision == null) {
            throw new IllegalArgumentException(
                    "Observed authorization decision is required");
        }

        if (persona == null) {
            throw new IllegalArgumentException(
                    "Observed authorization persona is required");
        }

        if (permission == null) {
            throw new IllegalArgumentException(
                    "Observed authorization permission is required");
        }

        if (reason == null) {
            throw new IllegalArgumentException(
                    "Observed authorization reason is required");
        }
    }
}
