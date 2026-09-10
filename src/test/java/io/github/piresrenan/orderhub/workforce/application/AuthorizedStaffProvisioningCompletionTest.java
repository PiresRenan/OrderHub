package io.github.piresrenan.orderhub.workforce.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import io.github.piresrenan.orderhub.authorization.application.port.in.provisioning.*;
import io.github.piresrenan.orderhub.authorization.domain.model.*;
import io.github.piresrenan.orderhub.tenants.application.port.in.operational.*;
import io.github.piresrenan.orderhub.users.application.port.in.*;
import io.github.piresrenan.orderhub.workforce.application.model.*;
import io.github.piresrenan.orderhub.workforce.application.port.out.*;
import io.github.piresrenan.orderhub.workforce.application.service.AuthorizedStaffProvisioningCompletion;

/**
 * Why: real completion must derive authority from owners and always append attribution.
 * Covers: lookup ordering, issuer membership, Tenant state and independent optional role.
 * Prevents: forged placement facts, stale issuer authority and unaudited success.
 */
class AuthorizedStaffProvisioningCompletionTest {
    private final StaffProvisioningFactsRepository facts = mock(StaffProvisioningFactsRepository.class);
    private final StaffProvisioningAuthorizationUseCase authorization = mock(StaffProvisioningAuthorizationUseCase.class);
    private final IsTenantMembershipOperationallyActiveUseCase memberships = mock(IsTenantMembershipOperationallyActiveUseCase.class);
    private final FindTenantOperationalStateUseCase tenants = mock(FindTenantOperationalStateUseCase.class);
    private final StaffProvisioningEvidenceRepository evidence = mock(StaffProvisioningEvidenceRepository.class);
    private final ConsumedStaffProvisioningIntent intent = new ConsumedStaffProvisioningIntent(UUID.randomUUID(),
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "OPERATOR", UUID.randomUUID());
    private final StaffProvisioningActor actor = new StaffProvisioningActor(intent.issuedByUserId(), intent.tenantId(),
            AuthorityBand.MANAGEMENT, PermissionEnvelope.of(Set.of(PermissionCode.TENANT_MEMBERS_MANAGE)));
    private final StaffProvisioningTarget target = new StaffProvisioningTarget(AuthorityBand.OPERATIONAL, PermissionEnvelope.none());

    @Test
    void requiresManagerBeforeResolvingTargetAndDelegationBeforeAssignment() {
        arrange();
        var user = UUID.randomUUID();
        var staff = UUID.randomUUID();
        var completion = completion();
        completion.authorize(intent);
        completion.complete(intent, user, staff);
        var order = inOrder(facts, authorization, evidence);
        order.verify(facts).actor(intent.issuedByUserId(), intent.tenantId());
        order.verify(authorization).requireManager(actor);
        order.verify(facts).target(intent.tenantId(), intent.departmentId(), intent.positionId());
        order.verify(authorization).requirePlacement(actor, target, "OPERATOR");
        order.verify(facts).actor(intent.issuedByUserId(), intent.tenantId());
        order.verify(authorization).requireManager(actor);
        order.verify(facts).target(intent.tenantId(), intent.departmentId(), intent.positionId());
        order.verify(authorization).assign(actor, target, user, "OPERATOR", intent.intentId(), intent.correlationId());
        order.verify(evidence).append(new StaffProvisioningEvidence(intent.tenantId(), intent.intentId(), intent.issuedByUserId(),
                user, staff, StaffProvisioningEvidence.Action.CONSUMED, intent.correlationId()));
    }

    @Test
    void terminatedIssuerCannotUseItsPreviouslyIssuedIntent() {
        assertThatThrownBy(() -> completion().authorize(intent)).isInstanceOf(RuntimeException.class)
                .hasMessage("Staff provisioning is unavailable");
        verifyNoInteractions(facts, authorization, evidence);
    }

    @Test
    void suspendedTenantPreventsPlacementAndRoleLookup() {
        when(memberships.isOperationallyActive(any())).thenReturn(true);
        when(tenants.find(any())).thenReturn(Optional.of(TenantOperationalState.SUSPENDED));
        assertThatThrownBy(() -> completion().authorize(intent)).isInstanceOf(RuntimeException.class)
                .hasMessage("Staff provisioning is unavailable");
        verifyNoInteractions(facts, authorization, evidence);
    }

    @Test
    void deniedManagerCannotProbeAPlacement() {
        arrange();
        doThrow(new StaffProvisioningAuthorizationDeniedException()).when(authorization).requireManager(actor);
        assertThatThrownBy(() -> completion().authorize(intent)).isInstanceOf(StaffProvisioningAuthorizationDeniedException.class);
        verify(facts, never()).target(any(), any(), any());
        verifyNoInteractions(evidence);
    }

    private void arrange() {
        when(memberships.isOperationallyActive(any())).thenReturn(true);
        when(tenants.find(any())).thenReturn(Optional.of(TenantOperationalState.ACTIVE));
        when(facts.actor(intent.issuedByUserId(), intent.tenantId())).thenReturn(Optional.of(actor));
        when(facts.target(intent.tenantId(), intent.departmentId(), intent.positionId())).thenReturn(Optional.of(target));
    }

    private StaffProvisioningCompletion completion() {
        return new AuthorizedStaffProvisioningCompletion(facts, authorization, memberships, tenants, evidence);
    }
}
