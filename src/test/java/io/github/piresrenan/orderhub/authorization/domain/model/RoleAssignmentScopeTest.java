package io.github.piresrenan.orderhub.authorization.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.Test;

class RoleAssignmentScopeTest {

    @Test
    void employeeRoleAssignmentRejectsCustomerPersona() {

        assertThatThrownBy(() ->
                new RoleAssignment(
                        UUID.randomUUID(),
                        AuthorizationPersona.CUSTOMER,
                        new TenantAuthorizationScope(
                                UUID.randomUUID()),
                        "ORDER_OPERATOR"))
                .isInstanceOf(
                        IllegalArgumentException.class)
                .hasMessage(
                        "Role assignments require the STAFF persona");
    }

    @Test
    void roleAssignmentRejectsUnstableRoleCode() {

        assertThatThrownBy(() ->
                new RoleAssignment(
                        UUID.randomUUID(),
                        AuthorizationPersona.STAFF,
                        new TenantAuthorizationScope(
                                UUID.randomUUID()),
                        "inventory manager"))
                .isInstanceOf(
                        IllegalArgumentException.class)
                .hasMessage(
                        "Role assignment code must use stable upper snake case");
    }

    @Test
    void directOverrideRetainsExplicitSubjectAndTenantScope() {

        var userId =
                UUID.randomUUID();

        var scope =
                new TenantAuthorizationScope(
                        UUID.randomUUID());

        var override =
                new UserPermissionOverride(
                        userId,
                        scope,
                        PermissionOverride.deny(
                                PermissionCode.INVENTORY_ADJUST));

        assertThat(override.userId())
                .isEqualTo(
                        userId);

        assertThat(override.scope())
                .isEqualTo(
                        scope);

        assertThat(
                override.appliesTo(
                        userId,
                        scope))
                .isTrue();
    }
}
