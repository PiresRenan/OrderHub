package io.github.piresrenan.orderhub.users.application.port.in;

import java.util.UUID;

/**
 * Carries the identities required to establish a User/Tenant association.
 *
 * <p>
 * Tenant identity remains represented only as UUID so this application boundary
 * does not depend on Tenant domain or persistence internals.
 * </p>
 *
 * @param userId internal User identifier
 * @param tenantId Tenant identifier to associate with the User
 */
public record EstablishTenantMembershipCommand(
        UUID userId,
        UUID tenantId) {
}
