package io.github.piresrenan.orderhub.authorization.application.port.in;

import io.github.piresrenan.orderhub.authorization.domain.model.AuthorizationDecision;
import io.github.piresrenan.orderhub.authorization.domain.model.PermissionEnvelope;
import io.github.piresrenan.orderhub.authorization.domain.model.TenantAuthorizationRequest;

/**
 * Framework-neutral boundary for one Tenant-scoped authorization decision.
 */
public interface AuthorizeTenantActionUseCase {

    AuthorizationDecision authorize(
            TenantAuthorizationRequest request,
            PermissionEnvelope actorEnvelope);
}
