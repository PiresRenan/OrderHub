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
    @Test
    void rehydratesPersistedOrganizationState() {

        var id = UUID.randomUUID();

        var organization = Organization.rehydrate(
                id,
                "Acme Retail",
                OrganizationStatus.ACTIVE);

        assertThat(organization.id())
                .isEqualTo(id);

        assertThat(organization.name())
                .isEqualTo("Acme Retail");

        assertThat(organization.status())
                .isEqualTo(OrganizationStatus.ACTIVE);
    }

    @Test
    void rehydrationRejectsInvalidPersistedOrganizationState() {

        var exception = assertThrows(
                IllegalArgumentException.class,
                () -> Organization.rehydrate(
                        UUID.randomUUID(),
                        "   ",
                        OrganizationStatus.ACTIVE));

        assertThat(exception)
                .hasMessage("Organization name must not be blank");
    }

    @Test
    void rehydrationRejectsNonNormalizedPersistedOrganizationName() {

        var exception = assertThrows(
                IllegalArgumentException.class,
                () -> Organization.rehydrate(
                        UUID.randomUUID(),
                        "  Acme Retail  ",
                        OrganizationStatus.ACTIVE));

        assertThat(exception)
                .hasMessage(
                        "Persisted organization name must be normalized");
    }

    @Test
    void rehydrationRejectsMissingOrganizationStatus() {

        var exception = assertThrows(
                IllegalArgumentException.class,
                () -> Organization.rehydrate(
                        UUID.randomUUID(),
                        "Acme Retail",
                        null));

        assertThat(exception)
                .hasMessage("Organization status is required");
    }
    @Test
    void transitionsOrganizationBetweenActiveAndSuspendedLifecycle()
            throws Exception {

        var statusNames =
                java.util.Arrays.stream(
                                OrganizationStatus.values())
                        .map(Enum::name)
                        .toList();

        var methodNames =
                java.util.Arrays.stream(
                                Organization.class
                                        .getDeclaredMethods())
                        .map(
                                java.lang.reflect.Method::getName)
                        .toList();

        org.junit.jupiter.api.Assertions.assertAll(
                () -> assertThat(statusNames)
                        .as(
                                "Organization lifecycle must include"
                                        + " SUSPENDED")
                        .contains("SUSPENDED"),

                () -> assertThat(methodNames)
                        .as(
                                "Organization lifecycle must expose"
                                        + " suspend()")
                        .contains("suspend"),

                () -> assertThat(methodNames)
                        .as(
                                "Organization lifecycle must expose"
                                        + " recover()")
                        .contains("recover"));

        var id =
                UUID.randomUUID();

        var active =
                Organization.create(
                        id,
                        "Acme Retail");

        var suspendMethod =
                Organization.class
                        .getMethod("suspend");

        var suspended =
                (Organization) suspendMethod.invoke(
                        active);

        assertThat(suspended.id())
                .isEqualTo(id);

        assertThat(suspended.name())
                .isEqualTo("Acme Retail");

        assertThat(suspended.status().name())
                .isEqualTo("SUSPENDED");

        assertThat(active.status())
                .isEqualTo(OrganizationStatus.ACTIVE);

        var recoverMethod =
                Organization.class
                        .getMethod("recover");

        var recovered =
                (Organization) recoverMethod.invoke(
                        suspended);

        assertThat(recovered.id())
                .isEqualTo(id);

        assertThat(recovered.name())
                .isEqualTo("Acme Retail");

        assertThat(recovered.status())
                .isEqualTo(OrganizationStatus.ACTIVE);

        assertThat(suspended.status().name())
                .isEqualTo("SUSPENDED");
    }
}
