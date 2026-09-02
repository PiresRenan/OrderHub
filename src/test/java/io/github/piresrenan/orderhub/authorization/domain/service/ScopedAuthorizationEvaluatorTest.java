package io.github.piresrenan.orderhub.authorization.domain.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.github.piresrenan.orderhub.authorization.domain.model.AuthorizationDecision;
import io.github.piresrenan.orderhub.authorization.domain.model.AuthorizationPersona;
import io.github.piresrenan.orderhub.authorization.domain.model.AuthorityBand;
import io.github.piresrenan.orderhub.authorization.domain.model.PermissionCode;
import io.github.piresrenan.orderhub.authorization.domain.model.PermissionEnvelope;
import io.github.piresrenan.orderhub.authorization.domain.model.PermissionOverride;
import io.github.piresrenan.orderhub.authorization.domain.model.RoleAssignment;
import io.github.piresrenan.orderhub.authorization.domain.model.RoleDefinition;
import io.github.piresrenan.orderhub.authorization.domain.model.RoleMutability;
import io.github.piresrenan.orderhub.authorization.domain.model.TenantAuthorizationRequest;
import io.github.piresrenan.orderhub.authorization.domain.model.TenantAuthorizationScope;
import io.github.piresrenan.orderhub.authorization.domain.model.UserPermissionOverride;

class ScopedAuthorizationEvaluatorTest {

    private final ScopedAuthorizationEvaluator evaluator =
            new ScopedAuthorizationEvaluator();

    @Test
    void assignedRoleAllowsPermissionOnlyInsideItsTenant() {

        var userId =
                UUID.randomUUID();

        var tenantA =
                scope();

        var tenantB =
                scope();

        var role =
                inventoryOperator();

        var assignments =
                List.of(
                        assignment(
                                userId,
                                tenantA,
                                role));

        var definitions =
                Map.of(
                        role.code(),
                        role);

        var envelope =
                inventoryEnvelope();

        assertThat(
                evaluator.evaluate(
                        request(
                                userId,
                                tenantA,
                                PermissionCode.INVENTORY_ADJUST),
                        assignments,
                        definitions,
                        envelope,
                        List.of()))
                .isEqualTo(
                        AuthorizationDecision.ALLOW);

        assertThat(
                evaluator.evaluate(
                        request(
                                userId,
                                tenantB,
                                PermissionCode.INVENTORY_ADJUST),
                        assignments,
                        definitions,
                        envelope,
                        List.of()))
                .isEqualTo(
                        AuthorizationDecision.DENY);
    }

    @Test
    void anotherUsersAssignmentDoesNotAuthorizeTheRequester() {

        var tenant =
                scope();

        var role =
                inventoryOperator();

        var assignments =
                List.of(
                        assignment(
                                UUID.randomUUID(),
                                tenant,
                                role));

        assertThat(
                evaluator.evaluate(
                        request(
                                UUID.randomUUID(),
                                tenant,
                                PermissionCode.INVENTORY_ADJUST),
                        assignments,
                        Map.of(
                                role.code(),
                                role),
                        inventoryEnvelope(),
                        List.of()))
                .isEqualTo(
                        AuthorizationDecision.DENY);
    }

    @Test
    void allowOverrideIsBoundToBothUserAndTenant() {

        var userA =
                UUID.randomUUID();

        var userB =
                UUID.randomUUID();

        var tenantA =
                scope();

        var tenantB =
                scope();

        var envelope =
                inventoryEnvelope();

        var override =
                new UserPermissionOverride(
                        userA,
                        tenantA,
                        PermissionOverride.allow(
                                PermissionCode.INVENTORY_ADJUST,
                                envelope));

        assertThat(
                evaluator.evaluate(
                        request(
                                userA,
                                tenantA,
                                PermissionCode.INVENTORY_ADJUST),
                        List.of(),
                        Map.of(),
                        envelope,
                        List.of(
                                override)))
                .isEqualTo(
                        AuthorizationDecision.ALLOW);

        assertThat(
                evaluator.evaluate(
                        request(
                                userA,
                                tenantB,
                                PermissionCode.INVENTORY_ADJUST),
                        List.of(),
                        Map.of(),
                        envelope,
                        List.of(
                                override)))
                .isEqualTo(
                        AuthorizationDecision.DENY);

        assertThat(
                evaluator.evaluate(
                        request(
                                userB,
                                tenantA,
                                PermissionCode.INVENTORY_ADJUST),
                        List.of(),
                        Map.of(),
                        envelope,
                        List.of(
                                override)))
                .isEqualTo(
                        AuthorizationDecision.DENY);
    }

    @Test
    void denyOverrideFromAnotherTenantDoesNotSuppressValidPermission() {

        var userId =
                UUID.randomUUID();

        var tenantA =
                scope();

        var tenantB =
                scope();

        var role =
                inventoryOperator();

        var denyInTenantA =
                new UserPermissionOverride(
                        userId,
                        tenantA,
                        PermissionOverride.deny(
                                PermissionCode.INVENTORY_ADJUST));

        assertThat(
                evaluator.evaluate(
                        request(
                                userId,
                                tenantB,
                                PermissionCode.INVENTORY_ADJUST),
                        List.of(
                                assignment(
                                        userId,
                                        tenantB,
                                        role)),
                        Map.of(
                                role.code(),
                                role),
                        inventoryEnvelope(),
                        List.of(
                                denyInTenantA)))
                .isEqualTo(
                        AuthorizationDecision.ALLOW);
    }

    @Test
    void missingRoleDefinitionForApplicableAssignmentFailsClosed() {

        var userId =
                UUID.randomUUID();

        var tenant =
                scope();

        var missingRole =
                new RoleAssignment(
                        userId,
                        AuthorizationPersona.STAFF,
                        tenant,
                        "INVENTORY_OPERATOR");

        assertThat(
                evaluator.evaluate(
                        request(
                                userId,
                                tenant,
                                PermissionCode.INVENTORY_VIEW),
                        List.of(
                                missingRole),
                        Map.of(),
                        inventoryEnvelope(),
                        List.of()))
                .isEqualTo(
                        AuthorizationDecision.DENY);
    }

    @Test
    void actorEnvelopeStillCapsAValidScopedRoleAssignment() {

        var userId =
                UUID.randomUUID();

        var tenant =
                scope();

        var role =
                inventoryOperator();

        var restrictedEnvelope =
                PermissionEnvelope.of(
                        EnumSet.of(
                                PermissionCode.INVENTORY_VIEW));

        assertThat(
                evaluator.evaluate(
                        request(
                                userId,
                                tenant,
                                PermissionCode.INVENTORY_ADJUST),
                        List.of(
                                assignment(
                                        userId,
                                        tenant,
                                        role)),
                        Map.of(
                                role.code(),
                                role),
                        restrictedEnvelope,
                        List.of()))
                .isEqualTo(
                        AuthorizationDecision.DENY);
    }

    @Test
    void customerPersonaNeverEntersTheStaffRbacPath() {

        var request =
                new TenantAuthorizationRequest(
                        UUID.randomUUID(),
                        AuthorizationPersona.CUSTOMER,
                        scope(),
                        PermissionCode.ORDERS_VIEW);

        assertThat(
                evaluator.evaluate(
                        request,
                        List.of(),
                        Map.of(),
                        PermissionEnvelope.of(
                                EnumSet.of(
                                        PermissionCode.ORDERS_VIEW)),
                        List.of()))
                .isEqualTo(
                        AuthorizationDecision.DENY);
    }

    private static TenantAuthorizationRequest request(
            UUID userId,
            TenantAuthorizationScope scope,
            PermissionCode permission) {

        return new TenantAuthorizationRequest(
                userId,
                AuthorizationPersona.STAFF,
                scope,
                permission);
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

    private static RoleDefinition inventoryOperator() {

        var permissions =
                EnumSet.of(
                        PermissionCode.INVENTORY_VIEW,
                        PermissionCode.INVENTORY_ADJUST);

        return new RoleDefinition(
                "INVENTORY_OPERATOR",
                AuthorizationPersona.STAFF,
                AuthorityBand.OPERATIONAL,
                RoleMutability.BUILTIN_FUNCTIONAL,
                permissions,
                PermissionEnvelope.of(
                        permissions));
    }

    private static PermissionEnvelope inventoryEnvelope() {

        return PermissionEnvelope.of(
                EnumSet.of(
                        PermissionCode.INVENTORY_VIEW,
                        PermissionCode.INVENTORY_ADJUST));
    }

    private static TenantAuthorizationScope scope() {

        return new TenantAuthorizationScope(
                UUID.randomUUID());
    }
}
