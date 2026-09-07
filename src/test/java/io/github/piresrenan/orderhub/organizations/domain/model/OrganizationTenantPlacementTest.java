package io.github.piresrenan.orderhub.organizations.domain.model;

import static org.assertj.core.api.Assertions.assertThat;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.Test;

class OrganizationTenantPlacementTest {

@Test
void createsPlacementBetweenOrganizationAndTenant() {

    var organizationId =
            UUID.randomUUID();

    var tenantId =
            UUID.randomUUID();

    var placement =
            OrganizationTenantPlacement.create(
                    organizationId,
                    tenantId);

    assertThat(placement.organizationId())
            .isEqualTo(organizationId);

    assertThat(placement.tenantId())
            .isEqualTo(tenantId);
}

@Test
void rejectsMissingOrganizationId() {

    assertThatThrownBy(() ->
            OrganizationTenantPlacement.create(
                    null,
                    UUID.randomUUID()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage(
                    "Placement organization id is required");
}

@Test
void rejectsMissingTenantId() {

    assertThatThrownBy(() ->
            OrganizationTenantPlacement.create(
                    UUID.randomUUID(),
                    null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage(
                    "Placement tenant id is required");
}

}
