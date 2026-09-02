package io.github.piresrenan.orderhub.authorization.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.github.piresrenan.orderhub.authorization.application.port.out.RoleAssignmentRepository;
import io.github.piresrenan.orderhub.authorization.application.port.out.RoleDefinitionRepository;
import io.github.piresrenan.orderhub.authorization.application.port.out.UserPermissionOverrideRepository;
import io.github.piresrenan.orderhub.authorization.domain.constraint.AuthorizationConstraint;
import io.github.piresrenan.orderhub.authorization.domain.constraint.StaticSeparationOfDutyConstraint;
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

class DurableTenantAuthorizationConstraintTest {

    @Test
    void durableAuthorizationAppliesConfiguredStaticSeparationOfDuty() {

        var userId =
                UUID.randomUUID();

        var scope =
                scope();

        var requester =
                role(
                        "REQUESTER_TEST",
                        PermissionCode.ORDERS_CREATE);

        var approver =
                role(
                        "APPROVER_TEST",
                        PermissionCode.ORDERS_APPROVE);

        var service =
                service(
                        userId,
                        scope,
                        Map.of(
                                requester.code(),
                                requester,
                                approver.code(),
                                approver),
                        List.of(
                                assignment(
                                        userId,
                                        scope,
                                        requester),
                                assignment(
                                        userId,
                                        scope,
                                        approver)),
                        List.of(
                                new StaticSeparationOfDutyConstraint(
                                        "REQUEST_APPROVE_TEST_SOD",
                                        Set.of(
                                                requester.code(),
                                                approver.code()))));

        assertThat(
                service.authorize(
                        request(
                                userId,
                                scope),
                        envelope()))
                .isEqualTo(
                        AuthorizationDecision.DENY);
    }

    @Test
    void constraintEvaluationFailureFailsClosed() {

        var userId =
                UUID.randomUUID();

        var scope =
                scope();

        var requester =
                role(
                        "REQUESTER_TEST",
                        PermissionCode.ORDERS_CREATE);

        AuthorizationConstraint failingConstraint =
                context -> {
                    throw new IllegalStateException(
                            "synthetic constraint failure");
                };

        var service =
                service(
                        userId,
                        scope,
                        Map.of(
                                requester.code(),
                                requester),
                        List.of(
                                assignment(
                                        userId,
                                        scope,
                                        requester)),
                        List.of(
                                failingConstraint));

        assertThat(
                service.authorize(
                        request(
                                userId,
                                scope),
                        envelope()))
                .isEqualTo(
                        AuthorizationDecision.DENY);
    }

    private static DurableTenantAuthorizationService service(
            UUID userId,
            TenantAuthorizationScope scope,
            Map<String, RoleDefinition> definitions,
            List<RoleAssignment> assignments,
            List<AuthorizationConstraint> constraints) {

        RoleAssignmentRepository assignmentRepository =
                new RoleAssignmentRepository() {

                    @Override
                    public void save(
                            RoleAssignment assignment) {

                        throw new UnsupportedOperationException();
                    }

                    @Override
                    public List<RoleAssignment> findByUserIdAndScope(
                            UUID requestedUserId,
                            TenantAuthorizationScope requestedScope) {

                        return assignments;
                    }
                };

        RoleDefinitionRepository roleRepository =
                (roleCode, requestedScope) ->
                        Optional.ofNullable(
                                definitions.get(
                                        roleCode));

        UserPermissionOverrideRepository overrideRepository =
                (requestedUserId, requestedScope) ->
                        List.of();

        return new DurableTenantAuthorizationService(
                assignmentRepository,
                roleRepository,
                overrideRepository,
                constraints);
    }

    private static RoleDefinition role(
            String code,
            PermissionCode permission) {

        var permissions =
                EnumSet.of(
                        permission);

        return new RoleDefinition(
                code,
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

    private static TenantAuthorizationScope scope() {

        return new TenantAuthorizationScope(
                UUID.randomUUID());
    }
}
