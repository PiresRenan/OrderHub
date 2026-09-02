package io.github.piresrenan.orderhub.authorization.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.github.piresrenan.orderhub.authorization.application.port.out.AuthorizationPersistenceException;
import io.github.piresrenan.orderhub.authorization.application.port.out.RoleAssignmentRepository;
import io.github.piresrenan.orderhub.authorization.application.port.out.RoleDefinitionRepository;
import io.github.piresrenan.orderhub.authorization.application.port.out.UserPermissionOverrideRepository;
import io.github.piresrenan.orderhub.authorization.domain.model.AuthorizationDecision;
import io.github.piresrenan.orderhub.authorization.domain.model.AuthorizationPersona;
import io.github.piresrenan.orderhub.authorization.domain.model.AuthorityBand;
import io.github.piresrenan.orderhub.authorization.domain.model.PermissionCode;
import io.github.piresrenan.orderhub.authorization.domain.model.PermissionEffect;
import io.github.piresrenan.orderhub.authorization.domain.model.PermissionEnvelope;
import io.github.piresrenan.orderhub.authorization.domain.model.PermissionOverride;
import io.github.piresrenan.orderhub.authorization.domain.model.RoleAssignment;
import io.github.piresrenan.orderhub.authorization.domain.model.RoleDefinition;
import io.github.piresrenan.orderhub.authorization.domain.model.RoleMutability;
import io.github.piresrenan.orderhub.authorization.domain.model.TenantAuthorizationRequest;
import io.github.piresrenan.orderhub.authorization.domain.model.TenantAuthorizationScope;
import io.github.piresrenan.orderhub.authorization.domain.model.UserPermissionOverride;

class DurableTenantAuthorizationServiceTest {

    @Test
    void allowsPermissionLoadedFromDurableRoleState() {

        var userId =
                UUID.randomUUID();

        var scope =
                scope();

        var role =
                inventoryOperator();

        var service =
                service(
                        List.of(
                                assignment(
                                        userId,
                                        scope,
                                        role)),
                        Map.of(
                                role.code(),
                                role),
                        List.of());

        assertThat(
                service.authorize(
                        request(
                                userId,
                                AuthorizationPersona.STAFF,
                                scope,
                                PermissionCode.INVENTORY_ADJUST),
                        inventoryEnvelope()))
                .isEqualTo(
                        AuthorizationDecision.ALLOW);
    }

    @Test
    void durableDenyOverrideWinsOverDurableRolePermission() {

        var userId =
                UUID.randomUUID();

        var scope =
                scope();

        var role =
                inventoryOperator();

        var override =
                new UserPermissionOverride(
                        userId,
                        scope,
                        new PermissionOverride(
                                PermissionCode.INVENTORY_ADJUST,
                                PermissionEffect.DENY));

        var service =
                service(
                        List.of(
                                assignment(
                                        userId,
                                        scope,
                                        role)),
                        Map.of(
                                role.code(),
                                role),
                        List.of(
                                override));

        assertThat(
                service.authorize(
                        request(
                                userId,
                                AuthorizationPersona.STAFF,
                                scope,
                                PermissionCode.INVENTORY_ADJUST),
                        inventoryEnvelope()))
                .isEqualTo(
                        AuthorizationDecision.DENY);
    }

    @Test
    void durableAllowOverrideMayGrantAbsentRolePermissionInsideEnvelope() {

        var userId =
                UUID.randomUUID();

        var scope =
                scope();

        var override =
                new UserPermissionOverride(
                        userId,
                        scope,
                        new PermissionOverride(
                                PermissionCode.INVENTORY_ADJUST,
                                PermissionEffect.ALLOW));

        var service =
                service(
                        List.of(),
                        Map.of(),
                        List.of(
                                override));

        assertThat(
                service.authorize(
                        request(
                                userId,
                                AuthorizationPersona.STAFF,
                                scope,
                                PermissionCode.INVENTORY_ADJUST),
                        inventoryEnvelope()))
                .isEqualTo(
                        AuthorizationDecision.ALLOW);
    }

    @Test
    void missingDurableRoleDefinitionFailsClosed() {

        var userId =
                UUID.randomUUID();

        var scope =
                scope();

        var assignment =
                new RoleAssignment(
                        userId,
                        AuthorizationPersona.STAFF,
                        scope,
                        "INVENTORY_OPERATOR");

        var service =
                service(
                        List.of(
                                assignment),
                        Map.of(),
                        List.of());

        assertThat(
                service.authorize(
                        request(
                                userId,
                                AuthorizationPersona.STAFF,
                                scope,
                                PermissionCode.INVENTORY_VIEW),
                        inventoryEnvelope()))
                .isEqualTo(
                        AuthorizationDecision.DENY);
    }

    @Test
    void persistenceFailureFailsClosed() {

        var assignments =
                new RoleAssignmentRepository() {

                    @Override
                    public void save(
                            RoleAssignment assignment) {

                        throw new UnsupportedOperationException();
                    }

                    @Override
                    public List<RoleAssignment> findByUserIdAndScope(
                            UUID userId,
                            TenantAuthorizationScope scope) {

                        throw new AuthorizationPersistenceException(
                                "synthetic persistence failure");
                    }
                };

        RoleDefinitionRepository roles =
                (roleCode, scope) ->
                        Optional.empty();

        UserPermissionOverrideRepository overrides =
                (userId, scope) ->
                        List.of();

        var service =
                new DurableTenantAuthorizationService(
                        assignments,
                        roles,
                        overrides);

        assertThat(
                service.authorize(
                        request(
                                UUID.randomUUID(),
                                AuthorizationPersona.STAFF,
                                scope(),
                                PermissionCode.INVENTORY_VIEW),
                        inventoryEnvelope()))
                .isEqualTo(
                        AuthorizationDecision.DENY);
    }

    @Test
    void foreignAssignmentReturnedByRepositoryFailsClosed() {

        var userId =
                UUID.randomUUID();

        var tenantA =
                scope();

        var tenantB =
                scope();

        var role =
                inventoryOperator();

        var validAllowOverride =
                new UserPermissionOverride(
                        userId,
                        tenantA,
                        new PermissionOverride(
                                PermissionCode.INVENTORY_ADJUST,
                                PermissionEffect.ALLOW));

        var service =
                service(
                        List.of(
                                assignment(
                                        userId,
                                        tenantB,
                                        role)),
                        Map.of(
                                role.code(),
                                role),
                        List.of(
                                validAllowOverride));

        assertThat(
                service.authorize(
                        request(
                                userId,
                                AuthorizationPersona.STAFF,
                                tenantA,
                                PermissionCode.INVENTORY_ADJUST),
                        inventoryEnvelope()))
                .isEqualTo(
                        AuthorizationDecision.DENY);
    }

    @Test
    void foreignOverrideReturnedByRepositoryFailsClosed() {

        var userId =
                UUID.randomUUID();

        var tenantA =
                scope();

        var tenantB =
                scope();

        var role =
                inventoryOperator();

        var foreignDeny =
                new UserPermissionOverride(
                        userId,
                        tenantB,
                        new PermissionOverride(
                                PermissionCode.INVENTORY_ADJUST,
                                PermissionEffect.DENY));

        var service =
                service(
                        List.of(
                                assignment(
                                        userId,
                                        tenantA,
                                        role)),
                        Map.of(
                                role.code(),
                                role),
                        List.of(
                                foreignDeny));

        assertThat(
                service.authorize(
                        request(
                                userId,
                                AuthorizationPersona.STAFF,
                                tenantA,
                                PermissionCode.INVENTORY_ADJUST),
                        inventoryEnvelope()))
                .isEqualTo(
                        AuthorizationDecision.DENY);
    }

    @Test
    void customerRequestNeverEntersStaffPersistencePath() {

        var assignments =
                new RoleAssignmentRepository() {

                    @Override
                    public void save(
                            RoleAssignment assignment) {

                        throw new AssertionError(
                                "Customer request must not touch STAFF persistence");
                    }

                    @Override
                    public List<RoleAssignment> findByUserIdAndScope(
                            UUID userId,
                            TenantAuthorizationScope scope) {

                        throw new AssertionError(
                                "Customer request must not touch STAFF persistence");
                    }
                };

        RoleDefinitionRepository roles =
                (roleCode, scope) -> {
                    throw new AssertionError(
                            "Customer request must not touch STAFF persistence");
                };

        UserPermissionOverrideRepository overrides =
                (userId, scope) -> {
                    throw new AssertionError(
                            "Customer request must not touch STAFF persistence");
                };

        var service =
                new DurableTenantAuthorizationService(
                        assignments,
                        roles,
                        overrides);

        assertThat(
                service.authorize(
                        request(
                                UUID.randomUUID(),
                                AuthorizationPersona.CUSTOMER,
                                scope(),
                                PermissionCode.ORDERS_VIEW),
                        PermissionEnvelope.none()))
                .isEqualTo(
                        AuthorizationDecision.DENY);
    }

    private static DurableTenantAuthorizationService service(
            List<RoleAssignment> assignments,
            Map<String, RoleDefinition> definitions,
            List<UserPermissionOverride> overrides) {

        var assignmentRepository =
                new RoleAssignmentRepository() {

                    @Override
                    public void save(
                            RoleAssignment assignment) {

                        throw new UnsupportedOperationException();
                    }

                    @Override
                    public List<RoleAssignment> findByUserIdAndScope(
                            UUID userId,
                            TenantAuthorizationScope scope) {

                        return assignments;
                    }
                };

        RoleDefinitionRepository roleRepository =
                (roleCode, scope) ->
                        Optional.ofNullable(
                                definitions.get(
                                        roleCode));

        UserPermissionOverrideRepository overrideRepository =
                (userId, scope) ->
                        overrides;

        return new DurableTenantAuthorizationService(
                assignmentRepository,
                roleRepository,
                overrideRepository);
    }

    private static TenantAuthorizationRequest request(
            UUID userId,
            AuthorizationPersona persona,
            TenantAuthorizationScope scope,
            PermissionCode permission) {

        return new TenantAuthorizationRequest(
                userId,
                persona,
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
