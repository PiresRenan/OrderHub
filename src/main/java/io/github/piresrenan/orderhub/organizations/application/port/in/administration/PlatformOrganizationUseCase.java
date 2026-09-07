package io.github.piresrenan.orderhub.organizations.application.port.in.administration;

import java.util.List;
import java.util.UUID;


public interface PlatformOrganizationUseCase {
    AdministrativeOrganization create(UUID actorUserId, String name, UUID correlationId);
    List<AdministrativeOrganization> list(UUID actorUserId);
    AdministrativeChangeResult suspend(UUID actorUserId, UUID organizationId, UUID correlationId);
    AdministrativeChangeResult recover(UUID actorUserId, UUID organizationId, UUID correlationId);
}
