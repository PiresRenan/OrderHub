package io.github.piresrenan.orderhub.organizations.application.port.out;

import java.util.Optional;
import java.util.List;
import java.util.UUID;

import io.github.piresrenan.orderhub.organizations.domain.model.OrganizationTenantPlacement;

public interface OrganizationTenantPlacementRepository {

    OrganizationTenantPlacementResult attach(
            UUID tenantId,
            UUID destinationOrganizationId);

    OrganizationTenantPlacementResult move(
            UUID tenantId,
            UUID expectedSourceOrganizationId,
            UUID destinationOrganizationId);

    OrganizationTenantPlacementResult detach(
            UUID tenantId,
            UUID expectedOrganizationId);

    Optional<OrganizationTenantPlacement> findByTenantId(
            UUID tenantId);

    List<UUID> findTenantIdsByOrganizationId(UUID organizationId);
}
