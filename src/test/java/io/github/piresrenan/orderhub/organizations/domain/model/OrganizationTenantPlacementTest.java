package io.github.piresrenan.orderhub.organizations.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class OrganizationTenantPlacementTest {

    private static final String PLACEMENT_TYPE =
            "io.github.piresrenan.orderhub.organizations.domain.model"
                    + ".OrganizationTenantPlacement";

    @Test
    void createsPlacementBetweenOrganizationAndTenant() {
        // Why: placement represents only the organizational association between
        // one Organization identity and one Tenant identity.
        // Covers: creation, exact two-UUID state and identity preservation.
        // Prevents: authorization, membership, lifecycle or other unrelated state
        // from leaking into the placement domain object.

        var organizationId =
                UUID.randomUUID();

        var tenantId =
                UUID.randomUUID();

        assertThatCode(() -> {
            var type =
                    Class.forName(
                            PLACEMENT_TYPE);

            var create =
                    type.getMethod(
                            "create",
                            UUID.class,
                            UUID.class);

            var placement =
                    create.invoke(
                            null,
                            organizationId,
                            tenantId);

            var instanceFields =
                    Arrays.stream(
                                    type.getDeclaredFields())
                            .filter(field ->
                                    !Modifier.isStatic(
                                            field.getModifiers()))
                            .toList();

            assertThat(instanceFields)
                    .as(
                            "Placement must contain exactly Organization"
                                    + " and Tenant identity state")
                    .extracting(
                            java.lang.reflect.Field::getName)
                    .containsExactly(
                            "organizationId",
                            "tenantId");

            assertThat(instanceFields)
                    .extracting(
                            java.lang.reflect.Field::getType)
                    .containsExactly(
                            UUID.class,
                            UUID.class);

            assertThat(
                    type.getMethod(
                                    "organizationId")
                            .invoke(
                                    placement))
                    .isEqualTo(
                            organizationId);

            assertThat(
                    type.getMethod(
                                    "tenantId")
                            .invoke(
                                    placement))
                    .isEqualTo(
                            tenantId);
        }).doesNotThrowAnyException();
    }

    @Test
    void rejectsMissingOrganizationId() {
        // Why: a placement without an Organization cannot represent a valid
        // organizational association.
        // Covers: required organizationId invariant.
        // Prevents: orphan placement state on the Organization side.

        assertThatThrownBy(() ->
                createPlacement(
                        null,
                        UUID.randomUUID()))
                .isInstanceOf(
                        java.lang.reflect.InvocationTargetException.class)
                .hasCauseInstanceOf(
                        IllegalArgumentException.class);
    }

    @Test
    void rejectsMissingTenantId() {
        // Why: a placement without a Tenant cannot represent a valid
        // organizational association.
        // Covers: required tenantId invariant.
        // Prevents: orphan placement state on the Tenant side.

        assertThatThrownBy(() ->
                createPlacement(
                        UUID.randomUUID(),
                        null))
                .isInstanceOf(
                        java.lang.reflect.InvocationTargetException.class)
                .hasCauseInstanceOf(
                        IllegalArgumentException.class);
    }

    private static Object createPlacement(
            UUID organizationId,
            UUID tenantId)
            throws Exception {

        var type =
                Class.forName(
                        PLACEMENT_TYPE);

        var create =
                type.getMethod(
                        "create",
                        UUID.class,
                        UUID.class);

        return create.invoke(
                null,
                organizationId,
                tenantId);
    }
}
