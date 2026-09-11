package io.github.piresrenan.orderhub.authorization.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import io.github.piresrenan.orderhub.authorization.application.port.in.provisioning.*;
import io.github.piresrenan.orderhub.authorization.application.port.out.*;
import io.github.piresrenan.orderhub.authorization.domain.model.*;
import io.github.piresrenan.orderhub.authorization.application.service.StaffProvisioningAuthorizationService;

/**
 * Why: a frozen role selector never authorizes its own assignment.
 * Covers: actual actor permissions, separate delegation bounds and RoleDelegationPolicy.
 * Prevents: stale/overbroad/self role grants and turning technical failure into denial.
 */
class StaffProvisioningAuthorizationServiceTest {
    private final RoleAssignmentRepository assignments = mock(RoleAssignmentRepository.class);
    private final RoleDefinitionRepository definitions = mock(RoleDefinitionRepository.class);
    private final UserPermissionOverrideRepository overrides = mock(UserPermissionOverrideRepository.class);
    private final StaffProvisioningAuthorizationRepository persistence = mock(StaffProvisioningAuthorizationRepository.class);
    private final UUID actorId = UUID.randomUUID();
    private final UUID tenant = UUID.randomUUID();
    private final TenantAuthorizationScope scope = new TenantAuthorizationScope(tenant);
    private final Set<PermissionCode> permissions = Set.of(PermissionCode.TENANT_MEMBERS_MANAGE,
            PermissionCode.TENANT_ROLES_ASSIGN, PermissionCode.INVENTORY_VIEW);
    private final StaffProvisioningActor actor = new StaffProvisioningActor(actorId, tenant,
            AuthorityBand.MANAGEMENT, PermissionEnvelope.of(permissions));
    private final StaffProvisioningTarget target = new StaffProvisioningTarget(AuthorityBand.OPERATIONAL,
            PermissionEnvelope.of(Set.of(PermissionCode.INVENTORY_VIEW)));

    @Test
    void appliesAuthorizedRoleAndRequiredEvidenceAfterStabilizingCurrentState() {
        arrange(permissions);
        var user = UUID.randomUUID();
        var intent = UUID.randomUUID();
        var correlation = UUID.randomUUID();
        when(assignments.findByUserIdAndScope(user, scope)).thenReturn(List.of());
        service().assign(actor, target, user, "OPERATOR", intent, correlation);
        var order = inOrder(persistence, assignments);
        order.verify(persistence).lock(actorId, tenant);
        order.verify(assignments).findByUserIdAndScope(actorId, scope);
        order.verify(persistence).lock(user, tenant);
        order.verify(assignments).findByUserIdAndScope(user, scope);
        order.verify(assignments).save(new RoleAssignment(user, AuthorizationPersona.STAFF, scope, "OPERATOR"));
        order.verify(persistence).append(actorId, tenant, user, "OPERATOR", intent, correlation, true);
    }

    @Test
    void missingMemberManagementDeniesBeforeLookingUpRequestedRole() {
        arrange(Set.of(PermissionCode.INVENTORY_VIEW));
        assertThatThrownBy(() -> service().requirePlacement(actor, target, "OPERATOR"))
                .isInstanceOf(StaffProvisioningAuthorizationDeniedException.class);
        verify(definitions, never()).findByCodeAndScope("OPERATOR", scope);
        verify(assignments, never()).save(any());
    }

    @Test
    void assignmentRequiresTheIndependentRoleAssignmentPermission() {
        arrange(Set.of(PermissionCode.TENANT_MEMBERS_MANAGE, PermissionCode.INVENTORY_VIEW));
        assertThatThrownBy(() -> service().assign(actor, target, UUID.randomUUID(), "OPERATOR", UUID.randomUUID(), UUID.randomUUID()))
                .isInstanceOf(StaffProvisioningAuthorizationDeniedException.class);
        verify(assignments, never()).save(any());
    }

    @Test
    void selfAssignmentRequiresPrivilegedAuthorityEvenForFunctionalRole() {
        arrange(permissions);
        assertThatThrownBy(() -> service().assign(actor, target, actorId, "OPERATOR", UUID.randomUUID(), UUID.randomUUID()))
                .isInstanceOf(StaffProvisioningAuthorizationDeniedException.class);
        verify(assignments, never()).save(any());
    }

    @Test
    void rejectsOverbroadPositionRatherThanClippingItsEnvelope() {
        arrange(permissions);
        var overbroad = new StaffProvisioningTarget(AuthorityBand.OPERATIONAL,
                PermissionEnvelope.of(Set.of(PermissionCode.INVENTORY_ADJUST)));
        assertThatThrownBy(() -> service().requirePlacement(actor, overbroad, null))
                .isInstanceOf(StaffProvisioningAuthorizationDeniedException.class);
    }

    @Test
    void actorSpecificAllowDoesNotInventDelegationAuthority() {
        arrange(Set.of(PermissionCode.TENANT_MEMBERS_MANAGE, PermissionCode.TENANT_ROLES_ASSIGN));
        when(overrides.findByUserIdAndScope(actorId, scope)).thenReturn(List.of(new UserPermissionOverride(
                actorId, scope, new PermissionOverride(PermissionCode.INVENTORY_VIEW, PermissionEffect.ALLOW))));
        assertThatThrownBy(() -> service().requirePlacement(actor, target, null))
                .isInstanceOf(StaffProvisioningAuthorizationDeniedException.class);
    }

    @Test
    void technicalStateFailureIsNotReportedAsPolicyDenial() {
        doThrow(new AuthorizationPersistenceException("Synthetic storage failure")).when(persistence).lock(actorId, tenant);
        assertThatThrownBy(() -> service().requireManager(actor)).isInstanceOf(AuthorizationPersistenceException.class);
    }

    private void arrange(Set<PermissionCode> rolePermissions) {
        when(assignments.findByUserIdAndScope(actorId, scope)).thenReturn(List.of(
                new RoleAssignment(actorId, AuthorizationPersona.STAFF, scope, "MANAGER")));
        when(definitions.findByCodeAndScope("MANAGER", scope)).thenReturn(Optional.of(new RoleDefinition(
                "MANAGER", AuthorizationPersona.STAFF, AuthorityBand.MANAGEMENT, RoleMutability.BUILTIN_FUNCTIONAL,
                rolePermissions, PermissionEnvelope.of(rolePermissions))));
        when(definitions.findByCodeAndScope("OPERATOR", scope)).thenReturn(Optional.of(new RoleDefinition(
                "OPERATOR", AuthorizationPersona.STAFF, AuthorityBand.OPERATIONAL, RoleMutability.BUILTIN_FUNCTIONAL,
                Set.of(PermissionCode.INVENTORY_VIEW), target.envelope())));
        when(overrides.findByUserIdAndScope(actorId, scope)).thenReturn(List.of());
    }

    private StaffProvisioningAuthorizationUseCase service() {
        return new StaffProvisioningAuthorizationService(assignments, definitions, overrides, persistence, List.of());
    }
}
