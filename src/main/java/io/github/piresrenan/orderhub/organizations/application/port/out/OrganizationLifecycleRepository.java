package io.github.piresrenan.orderhub.organizations.application.port.out;

import java.util.UUID;

import io.github.piresrenan.orderhub.organizations.domain.model.OrganizationStatus;

public interface OrganizationLifecycleRepository {

    OrganizationLifecycleMutationResult setStatus(
            UUID organizationId,
            OrganizationStatus desiredStatus);
}
