package io.github.piresrenan.orderhub.inventory.application.port.out;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import io.github.piresrenan.orderhub.inventory.domain.model.*;
/** Bounded owner-local current-state and operational-history reads. */
public interface InventoryAdministrationReadRepository {
    Optional<InventoryPosition> position(UUID tenant,UUID variant);
    Optional<InventoryPolicy> policy(UUID tenant);
    List<InventoryPosition> positions(UUID tenant,UUID afterVariant,int limit);
    List<InventoryMovement> movements(UUID tenant,UUID variant,UUID afterOperation,int limit);
}
