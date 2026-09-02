package io.github.piresrenan.orderhub.authorization.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.EnumSet;

import org.junit.jupiter.api.Test;

class AuthorizationKernelModelTest {

    @Test
    void roleRejectsPermissionsOutsideItsDefinitionEnvelope() {

        var envelope =
                PermissionEnvelope.of(
                        EnumSet.of(
                                PermissionCode.INVENTORY_VIEW));

        assertThatThrownBy(() ->
                new RoleDefinition(
                        "INVENTORY_MANAGER",
                        AuthorizationPersona.STAFF,
                        AuthorityBand.MANAGEMENT,
                        RoleMutability.BUILTIN_FUNCTIONAL,
                        EnumSet.of(
                                PermissionCode.INVENTORY_VIEW,
                                PermissionCode.INVENTORY_POLICY_MANAGE),
                        envelope))
                .isInstanceOf(
                        IllegalArgumentException.class)
                .hasMessage(
                        "Role permissions exceed its permission envelope");
    }

    @Test
    void customerPersonaCannotBeRepresentedAsAnEmployeeRole() {

        var envelope =
                PermissionEnvelope.of(
                        EnumSet.of(
                                PermissionCode.ORDERS_VIEW));

        assertThatThrownBy(() ->
                new RoleDefinition(
                        "CUSTOMER",
                        AuthorizationPersona.CUSTOMER,
                        AuthorityBand.OPERATIONAL,
                        RoleMutability.BUILTIN_FUNCTIONAL,
                        EnumSet.of(
                                PermissionCode.ORDERS_VIEW),
                        envelope))
                .isInstanceOf(
                        IllegalArgumentException.class)
                .hasMessage(
                        "Employee role definitions require the STAFF persona");
    }

    @Test
    void roleStateIsDefensivelyCopiedAndRetainsItsOrganizationalCeiling() {

        var permissions =
                EnumSet.of(
                        PermissionCode.INVENTORY_VIEW,
                        PermissionCode.INVENTORY_POLICY_MANAGE);

        var envelope =
                PermissionEnvelope.of(
                        permissions);

        var role =
                new RoleDefinition(
                        "INVENTORY_MANAGER",
                        AuthorizationPersona.STAFF,
                        AuthorityBand.MANAGEMENT,
                        RoleMutability.BUILTIN_FUNCTIONAL,
                        permissions,
                        envelope);

        permissions.clear();

        assertThat(role.permissions())
                .containsExactlyInAnyOrder(
                        PermissionCode.INVENTORY_VIEW,
                        PermissionCode.INVENTORY_POLICY_MANAGE);

        assertThat(role.authorityBand())
                .isEqualTo(
                        AuthorityBand.MANAGEMENT);

        assertThat(
                AuthorityBand.MANAGEMENT
                        .isAtLeast(
                                AuthorityBand.COORDINATION))
                .isTrue();
    }
}
