package io.github.piresrenan.orderhub.authorization.domain.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.github.piresrenan.orderhub.authorization.domain.model.AuthorityBand;
import io.github.piresrenan.orderhub.authorization.domain.model.AuthorizationDecision;
import io.github.piresrenan.orderhub.authorization.domain.model.AuthorizationPersona;
import io.github.piresrenan.orderhub.authorization.domain.model.PermissionCode;
import io.github.piresrenan.orderhub.authorization.domain.model.PermissionEnvelope;
import io.github.piresrenan.orderhub.authorization.domain.model.RoleAssignment;
import io.github.piresrenan.orderhub.authorization.domain.model.RoleDefinition;
import io.github.piresrenan.orderhub.authorization.domain.model.RoleMutability;
import io.github.piresrenan.orderhub.authorization.domain.model.TenantAuthorizationScope;

class RoleDelegationPolicyTest {

    private final RoleDelegationPolicy policy =
            new RoleDelegationPolicy();

    @Test
    void deniesWhenActorLacksExplicitRoleAssignmentPermission() {
        var actorUserId = UUID.randomUUID();
        var targetUserId = UUID.randomUUID();
        var scope = scope();

        var role =
                role(
                        "CATALOG_MANAGER",
                        AuthorityBand.MANAGEMENT,
                        RoleMutability.BUILTIN_FUNCTIONAL,
                        PermissionCode.CATALOG_MANAGE);

        var decision =
                policy.evaluate(
                        actorUserId,
                        scope,
                        AuthorityBand.MANAGEMENT,
                        Set.of(
                                PermissionCode.CATALOG_MANAGE),
                        envelope(
                                PermissionCode.CATALOG_MANAGE),
                        envelope(
                                PermissionCode.CATALOG_MANAGE),
                        assignment(
                                targetUserId,
                                scope,
                                role),
                        role);

        assertThat(decision)
                .isEqualTo(
                        AuthorizationDecision.DENY);
    }

    @Test
    void deniesSelfAssignmentWithoutPrivilegedDelegationPermission() {
        var actorUserId = UUID.randomUUID();
        var scope = scope();

        var role =
                role(
                        "INVENTORY_OPERATOR",
                        AuthorityBand.OPERATIONAL,
                        RoleMutability.BUILTIN_FUNCTIONAL,
                        PermissionCode.INVENTORY_VIEW);

        var decision =
                policy.evaluate(
                        actorUserId,
                        scope,
                        AuthorityBand.SUPERVISORY,
                        Set.of(
                                PermissionCode.TENANT_ROLES_ASSIGN),
                        envelope(
                                PermissionCode.INVENTORY_VIEW),
                        envelope(
                                PermissionCode.INVENTORY_VIEW),
                        assignment(
                                actorUserId,
                                scope,
                                role),
                        role);

        assertThat(decision)
                .isEqualTo(
                        AuthorizationDecision.DENY);
    }

    @Test
    void requiresPrivilegedDelegationForProtectedTenantGovernanceRole() {
        var actorUserId = UUID.randomUUID();
        var targetUserId = UUID.randomUUID();
        var scope = scope();

        var role =
                role(
                        "TENANT_ADMINISTRATOR",
                        AuthorityBand.TENANT_GOVERNANCE,
                        RoleMutability.TENANT_PROTECTED,
                        PermissionCode.TENANT_MEMBERS_MANAGE);

        var assignment =
                assignment(
                        targetUserId,
                        scope,
                        role);

        var ordinaryDecision =
                policy.evaluate(
                        actorUserId,
                        scope,
                        AuthorityBand.TENANT_GOVERNANCE,
                        Set.of(
                                PermissionCode.TENANT_ROLES_ASSIGN),
                        envelope(
                                PermissionCode.TENANT_MEMBERS_MANAGE),
                        envelope(
                                PermissionCode.TENANT_MEMBERS_MANAGE),
                        assignment,
                        role);

        var privilegedDecision =
                policy.evaluate(
                        actorUserId,
                        scope,
                        AuthorityBand.TENANT_GOVERNANCE,
                        Set.of(
                                PermissionCode.TENANT_ROLES_ASSIGN,
                                PermissionCode.TENANT_PRIVILEGED_ROLES_ASSIGN),
                        envelope(
                                PermissionCode.TENANT_MEMBERS_MANAGE),
                        envelope(
                                PermissionCode.TENANT_MEMBERS_MANAGE),
                        assignment,
                        role);

        assertThat(ordinaryDecision)
                .isEqualTo(
                        AuthorizationDecision.DENY);

        assertThat(privilegedDecision)
                .isEqualTo(
                        AuthorizationDecision.ALLOW);
    }

    @Test
    void deniesAssignmentOutsideScopeAuthorityOrPermissionEnvelopes() {
        var actorUserId = UUID.randomUUID();
        var targetUserId = UUID.randomUUID();

        var actorScope = scope();
        var otherScope = scope();

        var role =
                role(
                        "ORDER_MANAGER",
                        AuthorityBand.MANAGEMENT,
                        RoleMutability.BUILTIN_FUNCTIONAL,
                        PermissionCode.ORDERS_MANAGE);

        var allowedPermissions =
                Set.of(
                        PermissionCode.TENANT_ROLES_ASSIGN);

        assertThat(
                policy.evaluate(
                        actorUserId,
                        actorScope,
                        AuthorityBand.MANAGEMENT,
                        allowedPermissions,
                        envelope(
                                PermissionCode.ORDERS_MANAGE),
                        envelope(
                                PermissionCode.ORDERS_MANAGE),
                        assignment(
                                targetUserId,
                                otherScope,
                                role),
                        role))
                .isEqualTo(
                        AuthorizationDecision.DENY);

        assertThat(
                policy.evaluate(
                        actorUserId,
                        actorScope,
                        AuthorityBand.COORDINATION,
                        allowedPermissions,
                        envelope(
                                PermissionCode.ORDERS_MANAGE),
                        envelope(
                                PermissionCode.ORDERS_MANAGE),
                        assignment(
                                targetUserId,
                                actorScope,
                                role),
                        role))
                .isEqualTo(
                        AuthorizationDecision.DENY);

        assertThat(
                policy.evaluate(
                        actorUserId,
                        actorScope,
                        AuthorityBand.MANAGEMENT,
                        allowedPermissions,
                        PermissionEnvelope.none(),
                        envelope(
                                PermissionCode.ORDERS_MANAGE),
                        assignment(
                                targetUserId,
                                actorScope,
                                role),
                        role))
                .isEqualTo(
                        AuthorizationDecision.DENY);

        assertThat(
                policy.evaluate(
                        actorUserId,
                        actorScope,
                        AuthorityBand.MANAGEMENT,
                        allowedPermissions,
                        envelope(
                                PermissionCode.ORDERS_MANAGE),
                        PermissionEnvelope.none(),
                        assignment(
                                targetUserId,
                                actorScope,
                                role),
                        role))
                .isEqualTo(
                        AuthorizationDecision.DENY);
    }

    @Test
    void allowsOrdinaryRoleAssignmentInsideExplicitDelegationBoundary() {
        var actorUserId = UUID.randomUUID();
        var targetUserId = UUID.randomUUID();
        var scope = scope();

        var role =
                role(
                        "INVENTORY_RECEIVER",
                        AuthorityBand.OPERATIONAL,
                        RoleMutability.BUILTIN_FUNCTIONAL,
                        PermissionCode.INVENTORY_RECEIVE);

        var decision =
                policy.evaluate(
                        actorUserId,
                        scope,
                        AuthorityBand.SUPERVISORY,
                        Set.of(
                                PermissionCode.TENANT_ROLES_ASSIGN),
                        envelope(
                                PermissionCode.INVENTORY_RECEIVE),
                        envelope(
                                PermissionCode.INVENTORY_RECEIVE),
                        assignment(
                                targetUserId,
                                scope,
                                role),
                        role);

        assertThat(decision)
                .isEqualTo(
                        AuthorizationDecision.ALLOW);
    }

    private static RoleDefinition role(
            String code,
            AuthorityBand authorityBand,
            RoleMutability mutability,
            PermissionCode... permissions) {

        var permissionSet =
                Set.of(
                        permissions);

        return new RoleDefinition(
                code,
                AuthorizationPersona.STAFF,
                authorityBand,
                mutability,
                permissionSet,
                PermissionEnvelope.of(
                        permissionSet));
    }

    private static RoleAssignment assignment(
            UUID userId,
            TenantAuthorizationScope scope,
            RoleDefinition role) {

        return new RoleAssignment(
                userId,
                AuthorizationPersona.STAFF,
                scope,
                role.code());
    }

    private static PermissionEnvelope envelope(
            PermissionCode... permissions) {

        return PermissionEnvelope.of(
                Set.of(
                        permissions));
    }

    private static TenantAuthorizationScope scope() {
        return new TenantAuthorizationScope(
                UUID.randomUUID());
    }
}