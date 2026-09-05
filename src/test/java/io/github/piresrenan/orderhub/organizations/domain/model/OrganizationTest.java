package io.github.piresrenan.orderhub.organizations.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;

import org.junit.jupiter.api.Test;

class OrganizationTest {

    @Test
    void createsActiveOrganizationWithCanonicalName() {

        var id = UUID.randomUUID();

        var organization = Organization.create(
                id,
                "  Acme Retail  ");

        assertThat(organization.id())
                .isEqualTo(id);

        assertThat(organization.name())
                .isEqualTo("Acme Retail");

        assertThat(organization.status())
                .isEqualTo(OrganizationStatus.ACTIVE);
    }

    @Test
    void rejectsMissingOrganizationIdentity() {

        var exception = assertThrows(
                IllegalArgumentException.class,
                () -> Organization.create(
                        null,
                        "Acme Retail"));

        assertThat(exception)
                .hasMessage("Organization id is required");
    }
}
