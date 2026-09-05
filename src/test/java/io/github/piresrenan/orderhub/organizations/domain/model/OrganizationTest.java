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

    @Test
    void rejectsMissingOrganizationName() {

        var exception = assertThrows(
                IllegalArgumentException.class,
                () -> Organization.create(
                        UUID.randomUUID(),
                        null));

        assertThat(exception)
                .hasMessage("Organization name is required");
    }

    @Test
    void rejectsBlankOrganizationName() {

        var exception = assertThrows(
                IllegalArgumentException.class,
                () -> Organization.create(
                        UUID.randomUUID(),
                        "   "));

        assertThat(exception)
                .hasMessage("Organization name must not be blank");
    }

    @Test
    void acceptsOrganizationNameAt120CodePointBoundary() {

        var boundaryName =
                "A".repeat(119) + "\uD83D\uDE00";

        assertThat(boundaryName.codePointCount(
                0,
                boundaryName.length()))
                .isEqualTo(120);

        assertThat(boundaryName.length())
                .isEqualTo(121);

        var organization = Organization.create(
                UUID.randomUUID(),
                boundaryName);

        assertThat(organization.name())
                .isEqualTo(boundaryName);
    }

    @Test
    void rejectsOrganizationNameAbove120CodePointBoundary() {

        var tooLongName =
                "A".repeat(120) + "\uD83D\uDE00";

        assertThat(tooLongName.codePointCount(
                0,
                tooLongName.length()))
                .isEqualTo(121);

        assertThat(tooLongName.length())
                .isEqualTo(122);

        var exception = assertThrows(
                IllegalArgumentException.class,
                () -> Organization.create(
                        UUID.randomUUID(),
                        tooLongName));

        assertThat(exception)
                .hasMessage(
                        "Organization name must not exceed 120 characters");
    }
}
