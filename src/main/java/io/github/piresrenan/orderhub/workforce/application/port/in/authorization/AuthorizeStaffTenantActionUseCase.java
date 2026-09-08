package io.github.piresrenan.orderhub.workforce.application.port.in.authorization;

import java.util.UUID;

import io.github.piresrenan.orderhub.authorization.domain.model.AuthorizationDecision;
import io.github.piresrenan.orderhub.authorization.domain.model.PermissionCode;

/** Resolves current Staff authority and the existing Tenant permission kernel together. */
@FunctionalInterface
public interface AuthorizeStaffTenantActionUseCase {

    /**
     * Evaluates an action for internal User/Tenant identity already established by
     * the trusted request boundary. Membership alone grants no Staff permission.
     *
     * @throws StaffAuthorizationUnavailableException when authority cannot be established technically
     */
    AuthorizationDecision authorize(UUID userId, UUID tenantId, PermissionCode permission);
}
