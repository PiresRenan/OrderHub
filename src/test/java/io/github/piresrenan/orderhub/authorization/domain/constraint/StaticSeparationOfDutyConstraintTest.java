package io.github.piresrenan.orderhub.authorization.domain.constraint;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.github.piresrenan.orderhub.authorization.domain.model.AuthorizationDecision;
import io.github.piresrenan.orderhub.authorization.domain.model.AuthorizationPersona;
import io.github.piresrenan.orderhub.authorization.domain.model.AuthorityBand;
import io.github.piresrenan.orderhub.authorization.domain.model.PermissionCode;
import io.github.piresrenan.orderhub.authorization.domain.model.PermissionEnvelope;
import io.github.piresrenan.orderhub.authorization.domain.model.RoleAssignment;
import io.github.piresrenan.orderhub.authorization.domain.model.RoleDefinition;
import io.github.piresrenan.orderhub.authorization.domain.model.RoleMutability;
import io.github.piresrenan.orderhub.authorization.domain.model.TenantAuthorizationRequest;
import io.github.piresrenan.orderhub.authorization.domain.model.TenantAuthorizationScope;
import io.github.piresrenan.orderhub.authorization.domain.service.ScopedAuthorizationEvaluator;

class StaticSeparationOfDutyConstraintTest {

    private final ScopedAuthorizationEvaluator evaluator =
            new ScopedAuthorizationEvaluator();

    @Test
    void staticConstraintRequiresAtLeastTwoMutuallyExclusiveRoles() {

        assertThatThrownBy(() ->
                new StaticSeparationOfDutyConstraint(
                        "TEST_SOD",
                        Set.of(
                                "REQUESTER_TEST")))
                .isInstanceOf(
                        IllegalArgumentException.class)
                .hasMessage(
                        "Static separation of duty requires at least two roles");
    }

    @Test
    void conflictingRoleAssignmentsInSameScopeDenyOtherwiseEligibleAccess() {

        var userId =
                UUID.randomUUID();

        var scope =
                scope();

        var requester =
                requesterRole();

        var approver =
                approverRole();

        var assignments =
                List.of(
                        assignment(
                                userId,
                                scope,
                                requester),
                        assignment(
                                userId,
                                scope,
                                approver));

        assertThat(
                evaluator.evaluate(
                        request(
                                userId,
                                scope),
                        assignments,
                        definitions(
                                requester,
                                approver),
                        envelope(),
                        List.of(),
                        List.of(
                                constraint())))
                .isEqualTo(
                        AuthorizationDecision.DENY);
    }

    @Test
    void oneRoleFromMutuallyExclusiveSetDoesNotCreateADenial() {

        var userId =
                UUID.randomUUID();

        var scope =
                scope();

        var requester =
                requesterRole();

        assertThat(
                evaluator.evaluate(
                        request(
                                userId,
                                scope),
                        List.of(
                                assignment(
                                        userId,
                                        scope,
                                        requester)),
                        Map.of(
                                requester.code(),
                                requester),
                        envelope(),
                        List.of(),
                        List.of(
                                constraint())))
                .isEqualTo(
                        AuthorizationDecision.ALLOW);
    }

    @Test
    void conflictingRoleFromAnotherTenantDoesNotContaminateCurrentScope() {

        var userId =
                UUID.randomUUID();

        var tenantA =
                scope();

        var tenantB =
                scope();

        var requester =
                requesterRole();

        var approver =
                approverRole();

        assertThat(
                evaluator.evaluate(
                        request(
                                userId,
                                tenantA),
                        List.of(
                                assignment(
                                        userId,
                                        tenantA,
                                        requester),
                                assignment(
                                        userId,
                                        tenantB,
                                        approver)),
                        definitions(
                                requester,
                                approver),
                        envelope(),
                        List.of(),
                        List.of(
                                constraint())))
                .isEqualTo(
                        AuthorizationDecision.ALLOW);
    }

    private static StaticSeparationOfDutyConstraint constraint() {

        return new StaticSeparationOfDutyConstraint(
                "REQUEST_APPROVE_TEST_SOD",
                Set.of(
                        "REQUESTER_TEST",
                        "APPROVER_TEST"));
    }

    private static RoleDefinition requesterRole() {

        var permissions =
                EnumSet.of(
                        PermissionCode.ORDERS_CREATE);

        return new RoleDefinition(
                "REQUESTER_TEST",
                AuthorizationPersona.STAFF,
                AuthorityBand.OPERATIONAL,
                RoleMutability.BUILTIN_FUNCTIONAL,
                permissions,
                PermissionEnvelope.of(
                        permissions));
    }

    private static RoleDefinition approverRole() {

        var permissions =
                EnumSet.of(
                        PermissionCode.ORDERS_APPROVE);

        return new RoleDefinition(
                "APPROVER_TEST",
                AuthorizationPersona.STAFF,
                AuthorityBand.OPERATIONAL,
                RoleMutability.BUILTIN_FUNCTIONAL,
                permissions,
                PermissionEnvelope.of(
                        permissions));
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

    private static TenantAuthorizationRequest request(
            UUID userId,
            TenantAuthorizationScope scope) {

        return new TenantAuthorizationRequest(
                userId,
                AuthorizationPersona.STAFF,
                scope,
                PermissionCode.ORDERS_CREATE);
    }

    private static PermissionEnvelope envelope() {

        return PermissionEnvelope.of(
                EnumSet.of(
                        PermissionCode.ORDERS_CREATE,
                        PermissionCode.ORDERS_APPROVE));
    }

    private static Map<String, RoleDefinition> definitions(
            RoleDefinition first,
            RoleDefinition second) {

        return Map.of(
                first.code(),
                first,
                second.code(),
                second);
    }

    private static TenantAuthorizationScope scope() {

        return new TenantAuthorizationScope(
                UUID.randomUUID());
    }
}
