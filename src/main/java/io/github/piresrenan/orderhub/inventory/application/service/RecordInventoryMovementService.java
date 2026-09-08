package io.github.piresrenan.orderhub.inventory.application.service;

import io.github.piresrenan.orderhub.inventory.application.port.in.InventoryMovementCommand;
import io.github.piresrenan.orderhub.inventory.application.port.in.RecordInventoryMovementUseCase;
import io.github.piresrenan.orderhub.inventory.application.port.out.InventoryAdministrationAuthorization;
import io.github.piresrenan.orderhub.inventory.application.port.out.InventoryAdministrationTransactionExecutor;
import io.github.piresrenan.orderhub.inventory.application.port.out.InventoryMovementRepository;
import io.github.piresrenan.orderhub.inventory.application.port.out.InventoryTimeProvider;
import io.github.piresrenan.orderhub.inventory.application.port.out.InventoryVariantIdentityValidator;
import io.github.piresrenan.orderhub.inventory.domain.model.InventoryMovement;

/** Coordinates current authorization, durable operation ownership and physical stock. */
public final class RecordInventoryMovementService implements RecordInventoryMovementUseCase {
    private final InventoryAdministrationAuthorization authorization;
    private final InventoryAdministrationTransactionExecutor transactions;
    private final InventoryVariantIdentityValidator identities;
    private final InventoryMovementRepository movements;
    private final InventoryTimeProvider time;

    /** Requires authority, identity, transaction and durable movement ports with an authoritative clock. */
    public RecordInventoryMovementService(InventoryAdministrationAuthorization authorization,
            InventoryAdministrationTransactionExecutor transactions, InventoryVariantIdentityValidator identities,
            InventoryMovementRepository movements, InventoryTimeProvider time) {
        this.authorization = java.util.Objects.requireNonNull(authorization);
        this.transactions = java.util.Objects.requireNonNull(transactions);
        this.identities = java.util.Objects.requireNonNull(identities);
        this.movements = java.util.Objects.requireNonNull(movements);
        this.time = java.util.Objects.requireNonNull(time);
    }

    /** Authorizes before retry lookup and commits movement plus arithmetic as one unit. */
    @Override
    public InventoryMovement record(InventoryMovementCommand command) {
        java.util.Objects.requireNonNull(command, "command");
        var movement = new InventoryMovement(command.tenantId(), command.operationId(), command.actorUserId(),
                command.variantId(), command.type(), command.delta(), command.reason(),
                command.correlationId(), time.now());
        authorization.require(command.actorUserId(), command.tenantId(),
                command.type() == io.github.piresrenan.orderhub.inventory.domain.model.InventoryMovementType.RECEIPT
                        ? InventoryAdministrationAuthorization.Action.RECEIVE
                        : InventoryAdministrationAuthorization.Action.ADJUST);
        var fingerprint = InventoryMovementFingerprint.from(movement);
        return transactions.execute(() -> {
            var replay = movements.acquire(movement, fingerprint);
            if (replay.isPresent()) {
                return replay.orElseThrow();
            }
            identities.validate(command.tenantId(), command.variantId());
            movements.applyStockDelta(movement);
            return movement;
        });
    }
}
