package io.github.piresrenan.orderhub.workforce.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;

import io.github.piresrenan.orderhub.users.application.port.in.*;
import io.github.piresrenan.orderhub.workforce.application.model.ConsumedStaffProvisioningIntent;
import io.github.piresrenan.orderhub.workforce.application.port.out.*;
import io.github.piresrenan.orderhub.workforce.application.service.StaffProvisioningConsumptionService;

/**
 * Why: one-time consumption must coordinate every required step in one boundary.
 * Covers: exact secret decoding, non-enumerating rejection and failure propagation.
 * Prevents: downstream identity effects before proof and false partial success.
 */
class StaffProvisioningConsumptionServiceTest {
    private final StaffProvisioningIntentRepository intents = mock(StaffProvisioningIntentRepository.class);
    private final ResolveOrCreateExternalUserUseCase users = mock(ResolveOrCreateExternalUserUseCase.class);
    private final EnsureActiveTenantMembershipUseCase memberships = mock(EnsureActiveTenantMembershipUseCase.class);
    private final StaffMaterializationRepository staff = mock(StaffMaterializationRepository.class);
    private final StaffProvisioningCompletion completion = mock(StaffProvisioningCompletion.class);
    private final Clock clock = Clock.fixed(Instant.parse("2030-01-01T00:00:00Z"), ZoneOffset.UTC);
    private int transactionCalls;
    private boolean withinTransaction;

    @Test
    void completesOnlyAfterConsumptionAndCurrentAuthorityInsideOneBoundary() throws Exception {
        var intent = intent();
        var userId = UUID.randomUUID();
        var staffId = UUID.randomUUID();
        when(intents.consumePending(any(), any())).thenAnswer(call -> {
            assertThat(withinTransaction).isTrue();
            assertThat((byte[]) call.getArgument(0)).isEqualTo(MessageDigest.getInstance("SHA-256").digest(new byte[32]));
            return Optional.of(intent);
        });
        when(users.resolveOrCreate(any())).thenReturn(new ResolvedUserIdentity(userId));
        when(memberships.ensureActive(any())).thenReturn(new TenantMembershipEnsureResult.Operational());
        when(staff.materialize(intent.tenantId(), userId, intent.departmentId(), intent.positionId())).thenReturn(staffId);
        assertThat(consume(token())).isEqualTo(staffId);
        var order = inOrder(intents, completion, users, memberships, staff);
        order.verify(intents).consumePending(any(), any());
        order.verify(completion).authorize(intent);
        order.verify(users).resolveOrCreate(new ResolveExternalIdentityQuery("https://synthetic.test", "synthetic-subject"));
        order.verify(memberships).ensureActive(new EstablishTenantMembershipCommand(userId, intent.tenantId()));
        order.verify(staff).materialize(intent.tenantId(), userId, intent.departmentId(), intent.positionId());
        order.verify(completion).complete(intent, userId, staffId);
        assertThat(transactionCalls).isEqualTo(1);
    }

    @Test
    void unknownProofDoesNotProbeIdentityOrAuthority() {
        when(intents.consumePending(any(), any())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> consume(token())).isInstanceOf(RuntimeException.class)
                .hasMessage("Staff provisioning is unavailable");
        verifyNoInteractions(users, memberships, staff, completion);
    }

    @Test
    void malformedProofHasTheSameBoundedFailure() {
        for (var token : new String[] {"", "invalid", token() + "=", token().substring(1)}) {
            assertThatThrownBy(() -> consume(token)).isInstanceOf(RuntimeException.class)
                    .hasMessage("Staff provisioning is unavailable");
        }
        verifyNoInteractions(intents, users, memberships, staff, completion);
    }

    @Test
    void nonOperationalMembershipAbortsInsteadOfCreatingStaff() {
        when(intents.consumePending(any(), any())).thenReturn(Optional.of(intent()));
        when(users.resolveOrCreate(any())).thenReturn(new ResolvedUserIdentity(UUID.randomUUID()));
        when(memberships.ensureActive(any())).thenReturn(new TenantMembershipEnsureResult.NonOperational());
        assertThatThrownBy(() -> consume(token())).isInstanceOf(RuntimeException.class)
                .hasMessage("Staff provisioning is unavailable");
        verifyNoInteractions(staff);
        verify(completion, never()).complete(any(), any(), any());
    }

    @Test
    void authorityFailurePreventsIdentityCreation() {
        when(intents.consumePending(any(), any())).thenReturn(Optional.of(intent()));
        doThrow(new IllegalStateException("Synthetic authority failure")).when(completion).authorize(any());
        assertThatThrownBy(() -> consume(token())).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(users, memberships, staff);
    }

    private UUID consume(String secret) {
        WorkforceTransactionExecutor transaction = new WorkforceTransactionExecutor() {
            @Override public <T> T execute(Supplier<T> work) {
                transactionCalls++;
                withinTransaction = true;
                try { return work.get(); } finally { withinTransaction = false; }
            }
        };
        return new StaffProvisioningConsumptionService(intents, users, memberships, staff,
                completion, transaction, clock).consume(secret, "https://synthetic.test", "synthetic-subject");
    }

    private String token() { return Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]); }

    private ConsumedStaffProvisioningIntent intent() {
        return new ConsumedStaffProvisioningIntent(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), null, UUID.randomUUID());
    }
}
