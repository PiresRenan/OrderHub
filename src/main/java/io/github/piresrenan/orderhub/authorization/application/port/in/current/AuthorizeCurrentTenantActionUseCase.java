package io.github.piresrenan.orderhub.authorization.application.port.in.current;

import io.github.piresrenan.orderhub.authorization.domain.model.AuthorizationDecision;
import io.github.piresrenan.orderhub.authorization.domain.model.TenantAuthorizationRequest;

/** Reads the organizational ceiling and durable role policy in one authorization snapshot. */
@FunctionalInterface
public interface AuthorizeCurrentTenantActionUseCase {

    /**
     * Evaluates current authority without turning a technical lookup failure into DENY.
     * The source executes in the same snapshot as assignments, role permissions and overrides.
     *
     * @throws TenantAuthorizationUnavailableException when current authority cannot be established
     */
    AuthorizationDecision authorizeCurrent(
            TenantAuthorizationRequest request, CurrentPermissionEnvelopeSource envelopeSource);
}
