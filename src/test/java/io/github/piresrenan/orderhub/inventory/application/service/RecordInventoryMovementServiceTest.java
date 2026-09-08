package io.github.piresrenan.orderhub.inventory.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import io.github.piresrenan.orderhub.inventory.application.port.in.*;
import io.github.piresrenan.orderhub.inventory.application.port.out.*;
import io.github.piresrenan.orderhub.inventory.domain.model.*;

/** Orchestration assertions protect authorization-before-replay and exactly one stock effect. */
class RecordInventoryMovementServiceTest {
    private final InventoryAdministrationAuthorization authorization = mock(InventoryAdministrationAuthorization.class);
    private final InventoryVariantIdentityValidator identities = mock(InventoryVariantIdentityValidator.class);
    private final InventoryMovementRepository movements = mock(InventoryMovementRepository.class);
    private final Instant now = Instant.parse("2026-09-07T10:00:00Z");
    private final InventoryAdministrationTransactionExecutor transaction = new InventoryAdministrationTransactionExecutor() {
        public <T> T execute(Supplier<T> work) { return work.get(); }
    };
    private final RecordInventoryMovementService service = new RecordInventoryMovementService(
            authorization, transaction, identities, movements, () -> now);

    private InventoryMovementCommand command(InventoryMovementType type, long delta) {
        return new InventoryMovementCommand(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), type, delta, "STOCK_COUNT", UUID.randomUUID());
    }

    @Test
    void receiptAuthorizesThenAcquiresIdentityBeforeValidatingVariantAndApplyingStock() {
        var command = command(InventoryMovementType.RECEIPT, 12);
        when(movements.acquire(any(), anyString())).thenReturn(Optional.empty());
        var result = service.record(command);
        assertThat(result.delta()).isEqualTo(12);
        assertThat(result.occurredAt()).isEqualTo(now);
        var order = inOrder(authorization, movements, identities);
        order.verify(authorization).require(command.actorUserId(), command.tenantId(),
                InventoryAdministrationAuthorization.Action.RECEIVE);
        order.verify(movements).acquire(eq(result), anyString());
        order.verify(identities).validate(command.tenantId(), command.variantId());
        order.verify(movements).applyStockDelta(result);
    }

    @Test
    void replayReturnsOriginalFactWithoutNewIdentityCheckOrStockEffect() {
        var command = command(InventoryMovementType.ADJUSTMENT, -2);
        var original = new InventoryMovement(command.tenantId(), command.operationId(), command.actorUserId(),
                command.variantId(), command.type(), command.delta(), command.reason(),
                UUID.randomUUID(), now.minusSeconds(1));
        when(movements.acquire(any(), anyString())).thenReturn(Optional.of(original));
        assertThat(service.record(command)).isSameAs(original);
        verify(authorization).require(command.actorUserId(), command.tenantId(),
                InventoryAdministrationAuthorization.Action.ADJUST);
        verifyNoInteractions(identities);
        verify(movements, never()).applyStockDelta(any());
    }

    @Test
    void deniedActorCannotProbeTargetOrMovement() {
        doThrow(new InventoryAdministrationException(InventoryAdministrationException.Reason.ACCESS_DENIED))
                .when(authorization).require(any(), any(), any());
        assertThatThrownBy(() -> service.record(command(InventoryMovementType.RECEIPT, 1)))
                .isInstanceOf(InventoryAdministrationException.class);
        verifyNoInteractions(movements, identities);
    }

    @Test
    void rejectsZeroAndNegativeReceiptsAndZeroAdjustmentBeforePersistence() {
        assertThatThrownBy(() -> service.record(command(InventoryMovementType.RECEIPT, 0)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.record(command(InventoryMovementType.RECEIPT, -1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.record(command(InventoryMovementType.ADJUSTMENT, 0)))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(movements, identities);
    }
}
