package io.github.piresrenan.orderhub.inventory.application.service;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import io.github.piresrenan.orderhub.inventory.application.port.in.InventoryAdministrationException;
import io.github.piresrenan.orderhub.inventory.application.port.in.InventoryAdministrationException.Reason;
import io.github.piresrenan.orderhub.inventory.application.port.out.*;
import io.github.piresrenan.orderhub.inventory.domain.model.*;
/** VIEW authority is required independently of every Inventory mutation permission. */
public final class InventoryAdministrationReadService {
    private final InventoryAdministrationAuthorization authorization;
    private final InventoryAdministrationTransactionExecutor transactions;
    private final InventoryAdministrationReadRepository repository;
    public InventoryAdministrationReadService(InventoryAdministrationAuthorization authorization,InventoryAdministrationTransactionExecutor transactions,
            InventoryAdministrationReadRepository repository) {
        this.authorization=Objects.requireNonNull(authorization); this.transactions=Objects.requireNonNull(transactions);
        this.repository=Objects.requireNonNull(repository);
    }
    public InventoryPosition position(UUID actor,UUID tenant,UUID variant) {
        authorize(actor,tenant);
        return transactions.execute(()->repository.position(tenant,variant).orElseThrow(()->new InventoryAdministrationException(Reason.TARGET_UNAVAILABLE)));
    }
    public InventoryPolicy policy(UUID actor,UUID tenant) {
        authorize(actor,tenant);
        return transactions.execute(()->repository.policy(tenant).orElseThrow(()->new InventoryAdministrationException(Reason.TARGET_UNAVAILABLE)));
    }
    public List<InventoryPosition> positions(UUID actor,UUID tenant,UUID afterVariant,int limit) {
        authorize(actor,tenant); bounded(limit);
        return transactions.execute(()->repository.positions(tenant,afterVariant,limit));
    }
    public List<InventoryMovement> movements(UUID actor,UUID tenant,UUID variant,UUID afterOperation,int limit) {
        authorize(actor,tenant); bounded(limit);
        return transactions.execute(()-> {
            repository.position(tenant,variant).orElseThrow(()->new InventoryAdministrationException(Reason.TARGET_UNAVAILABLE));
            return repository.movements(tenant,variant,afterOperation,limit);
        });
    }
    private void authorize(UUID actor,UUID tenant) { authorization.require(actor,tenant,InventoryAdministrationAuthorization.Action.VIEW); }
    private static void bounded(int limit) { if(limit<1 || limit>100) throw new IllegalArgumentException("Invalid page size"); }
}
