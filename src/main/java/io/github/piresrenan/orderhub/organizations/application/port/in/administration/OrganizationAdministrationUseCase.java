package io.github.piresrenan.orderhub.organizations.application.port.in.administration;

import java.util.List;
import java.util.UUID;

public interface OrganizationAdministrationUseCase {
    void attachTenant(UUID actorUserId, UUID organizationId, UUID tenantId, UUID correlationId);

    void moveTenant(UUID actorUserId, UUID expectedSourceOrganizationId,
            UUID destinationOrganizationId, UUID tenantId, UUID correlationId);

    void detachTenant(UUID actorUserId, UUID expectedOrganizationId,
            UUID tenantId, UUID correlationId);

    void grant(UUID actorUserId, UUID organizationId, UUID userId,
            String permissionCode, UUID correlationId);

    void revoke(UUID actorUserId, UUID organizationId, UUID userId,
            String permissionCode, UUID correlationId);

    List<OrganizationTenantSummary> listTenants(UUID actorUserId, UUID organizationId);
}
