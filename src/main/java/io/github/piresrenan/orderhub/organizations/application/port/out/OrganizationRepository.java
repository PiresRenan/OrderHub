package io.github.piresrenan.orderhub.organizations.application.port.out;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import io.github.piresrenan.orderhub.organizations.domain.model.Organization;

public interface OrganizationRepository {

    Organization save(
            Organization organization);

    Optional<Organization> findById(
            UUID organizationId);

    List<Organization> findAll();
}
