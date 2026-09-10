package io.github.piresrenan.orderhub.workforce.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import io.github.piresrenan.orderhub.authorization.application.port.in.provisioning.*;
import io.github.piresrenan.orderhub.authorization.domain.model.*;
import io.github.piresrenan.orderhub.tenants.application.port.in.operational.*;
import io.github.piresrenan.orderhub.users.application.port.in.*;
import io.github.piresrenan.orderhub.workforce.application.model.*;
import io.github.piresrenan.orderhub.workforce.application.port.in.IssueStaffProvisioningIntentUseCase;
import io.github.piresrenan.orderhub.workforce.application.port.out.*;
import io.github.piresrenan.orderhub.workforce.application.port.in.authorization.AuthorizeStaffTenantActionUseCase;
import io.github.piresrenan.orderhub.workforce.application.service.StaffProvisioningAdministrationService;

/** Proves authority precedes sensitive issuance/cancellation and required evidence follows each applied change. */
class StaffProvisioningAdministrationServiceTest {
    private final StaffProvisioningFactsRepository facts = mock(StaffProvisioningFactsRepository.class);
    private final StaffProvisioningAuthorizationUseCase authorization = mock(StaffProvisioningAuthorizationUseCase.class);
    private final IsTenantMembershipOperationallyActiveUseCase memberships = mock(IsTenantMembershipOperationallyActiveUseCase.class);
    private final FindTenantOperationalStateUseCase tenants = mock(FindTenantOperationalStateUseCase.class);
    private final StaffProvisioningEvidenceRepository evidence = mock(StaffProvisioningEvidenceRepository.class);
    private final IssueStaffProvisioningIntentUseCase primitive = mock(IssueStaffProvisioningIntentUseCase.class);
    private final StaffProvisioningIntentRepository intents = mock(StaffProvisioningIntentRepository.class);
    private final AuthorizeStaffTenantActionUseCase readAuthority = mock(AuthorizeStaffTenantActionUseCase.class);
    private final WorkforceTransactionExecutor transaction = new WorkforceTransactionExecutor() {
        public <T> T execute(Supplier<T> work) { return work.get(); }
    };
    private final IssueStaffProvisioningIntentCommand command = new IssueStaffProvisioningIntentCommand(
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), null, UUID.randomUUID(), UUID.randomUUID());
    private final StaffProvisioningActor actor = new StaffProvisioningActor(command.issuedByUserId(), command.tenantId(),
            AuthorityBand.MANAGEMENT, PermissionEnvelope.of(Set.of(PermissionCode.TENANT_MEMBERS_MANAGE)));
    private final StaffProvisioningTarget target = new StaffProvisioningTarget(AuthorityBand.OPERATIONAL, PermissionEnvelope.none());

    @Test
    void authorizedIssuanceChecksPlacementBeforeCreatingAndAuditing() throws Exception {
        arrange();
        var issued = new StaffProvisioningIssuance.Issued(UUID.randomUUID(), "synthetic-unit-proof", OffsetDateTime.now().plusMinutes(5));
        when(primitive.issue(command)).thenReturn(issued);
        assertThat(service().issue(command)).isEqualTo(issued);
        var order = inOrder(authorization, facts, primitive, evidence);
        order.verify(facts).actor(command.issuedByUserId(), command.tenantId());
        order.verify(authorization).requireManager(actor);
        order.verify(facts).target(command.tenantId(), command.departmentId(), command.positionId());
        order.verify(authorization).requirePlacement(actor, target, null);
        order.verify(primitive).issue(command);
        order.verify(evidence).append(new StaffProvisioningEvidence(command.tenantId(), issued.intentId(), command.issuedByUserId(),
                null, null, StaffProvisioningEvidence.Action.ISSUED, command.correlationId()));
    }

    @Test
    void deniedManagerCannotProbeTargetsOrCreateProofs() {
        arrange();
        doThrow(new StaffProvisioningAuthorizationDeniedException()).when(authorization).requireManager(actor);
        assertThatThrownBy(() -> service().issue(command)).isInstanceOf(StaffProvisioningAuthorizationDeniedException.class);
        verify(facts, never()).target(any(), any(), any());
        verifyNoInteractions(primitive, intents, evidence);
    }

    @Test
    void replayDoesNotCreateAnotherIssuanceEvent() throws Exception {
        arrange();
        var replay = new StaffProvisioningIssuance.Replay(UUID.randomUUID());
        when(primitive.issue(command)).thenReturn(replay);
        assertThat(service().issue(command)).isEqualTo(replay);
        verifyNoInteractions(evidence);
    }

    @Test
    void cancellationUsesAuthorizedTenantAndIntentWithoutSecretLookup() throws Exception {
        arrange();
        var intentId = UUID.randomUUID();
        when(intents.cancelPending(eq(command.tenantId()), eq(intentId), any())).thenReturn(true);
        assertThat(service().cancel(command.issuedByUserId(), command.tenantId(), intentId, command.correlationId())).isTrue();
        var order = inOrder(readAuthority, authorization, intents, evidence);
        order.verify(readAuthority).authorize(command.issuedByUserId(), command.tenantId(), PermissionCode.TENANT_MEMBERS_MANAGE);
        order.verify(intents).cancelPending(eq(command.tenantId()), eq(intentId), any());
        order.verify(authorization).requireManager(actor);
        order.verify(evidence).append(new StaffProvisioningEvidence(command.tenantId(), intentId, command.issuedByUserId(),
                null, null, StaffProvisioningEvidence.Action.CANCELLED, command.correlationId()));
        verify(facts, never()).target(any(), any(), any());
    }

    @Test
    void nonOperationalIssuerCannotCancelOrLearnIntentState() {
        assertThatThrownBy(() -> service().cancel(command.issuedByUserId(), command.tenantId(), UUID.randomUUID(), command.correlationId()))
                .isInstanceOf(RuntimeException.class).hasMessage("Staff provisioning is unavailable");
        verifyNoInteractions(facts, authorization, primitive, intents, evidence);
    }

    private void arrange() {
        when(readAuthority.authorize(any(), any(), any())).thenReturn(AuthorizationDecision.ALLOW);
        when(memberships.isOperationallyActive(any())).thenReturn(true);
        when(tenants.find(any())).thenReturn(Optional.of(TenantOperationalState.ACTIVE));
        when(facts.actor(command.issuedByUserId(), command.tenantId())).thenReturn(Optional.of(actor));
        when(facts.target(command.tenantId(), command.departmentId(), command.positionId())).thenReturn(Optional.of(target));
    }

    private StaffProvisioningAdministrationService service() {
        return new StaffProvisioningAdministrationService(facts, authorization, memberships, tenants, evidence,
                primitive, intents, transaction, Clock.systemUTC(), readAuthority);
    }
}
