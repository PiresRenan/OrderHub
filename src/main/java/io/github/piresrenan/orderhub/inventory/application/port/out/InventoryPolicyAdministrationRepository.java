package io.github.piresrenan.orderhub.inventory.application.port.out;
import java.util.UUID;
import java.util.Optional;
import java.time.Instant;
import io.github.piresrenan.orderhub.inventory.domain.model.*;
/** Owner-local persistence boundary for policy administration. */
public interface InventoryPolicyAdministrationRepository {
    /** Acquires the current policy row for desired-state comparison. */
    Optional<InventoryPolicy> lockPolicy(UUID tenant);
    /** Creates missing policy once, coordinating concurrent first use. */
    boolean initializePolicy(UUID tenant,InventoryPolicy desired);
    /** Updates the policy held by this transaction. */
    void savePolicy(UUID tenant,InventoryPolicy desired);
    /** Acquires the exact Position for availability-threshold changes. */
    Optional<InventoryPosition> lockPosition(UUID tenant,UUID variant);
    /** Updates only the locked Position's safety-stock field. */
    void saveSafety(UUID tenant,UUID variant,long desired);
    /** Appends bounded prior/desired policy facts inside the same transaction. */
    void appendPolicy(UUID actor,UUID tenant,InventoryPolicy before,InventoryPolicy after,String reason,UUID correlation,Instant time);
    /** Appends bounded prior/desired safety facts inside the same transaction. */
    void appendSafety(UUID actor,UUID tenant,UUID variant,long before,long after,String reason,UUID correlation,Instant time);
}
