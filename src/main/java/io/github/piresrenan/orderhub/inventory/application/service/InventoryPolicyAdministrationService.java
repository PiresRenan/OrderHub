package io.github.piresrenan.orderhub.inventory.application.service;
import java.util.UUID;
import io.github.piresrenan.orderhub.inventory.application.port.out.*;
import io.github.piresrenan.orderhub.inventory.domain.model.*;
import io.github.piresrenan.orderhub.inventory.application.port.in.InventoryAdministrationException;
import io.github.piresrenan.orderhub.inventory.application.port.in.InventoryAdministrationException.Reason;
/** Explicit desired-state administration of Inventory policy and availability threshold. */
public final class InventoryPolicyAdministrationService {
    private final InventoryAdministrationAuthorization authorization;
    private final InventoryAdministrationTransactionExecutor transactions;
    private final InventoryPolicyAdministrationRepository repository;
    private final InventoryTimeProvider time;
    /** Accepts owner ports for the policy administration TDD boundary. */
    public InventoryPolicyAdministrationService(InventoryAdministrationAuthorization a,InventoryAdministrationTransactionExecutor t,
            InventoryPolicyAdministrationRepository r,InventoryTimeProvider time) {
        authorization=java.util.Objects.requireNonNull(a); transactions=java.util.Objects.requireNonNull(t);
        repository=java.util.Objects.requireNonNull(r); this.time=java.util.Objects.requireNonNull(time);
    }
    /** Sets the desired policy only against an explicit expected prior state. */
    public InventoryPolicy policy(UUID actor,UUID tenant,InventoryPolicy expected,InventoryPolicy desired,String reason,UUID correlation) {
        authorization.require(actor,tenant,InventoryAdministrationAuthorization.Action.POLICY_MANAGE);
        requireEvidence(actor,tenant,reason,correlation);
        java.util.Objects.requireNonNull(desired,"desired policy");
        return transactions.execute(()-> {
            if(expected==null && repository.initializePolicy(tenant,desired)) {
                repository.appendPolicy(actor,tenant,null,desired,reason,correlation,time.now());
                return desired;
            }
            var current=repository.lockPolicy(tenant).orElseThrow(()->new InventoryAdministrationException(Reason.TARGET_UNAVAILABLE));
            if(current==desired) return current;
            if(current!=expected) throw new InventoryAdministrationException(Reason.CONFLICT);
            repository.savePolicy(tenant,desired);
            repository.appendPolicy(actor,tenant,current,desired,reason,correlation,time.now());
            return desired;
        });
    }
    /** Sets safety stock without changing physical or Order allocation counters. */
    public InventoryPosition safetyStock(UUID actor,UUID tenant,UUID variant,long expected,long desired,String reason,UUID correlation) {
        authorization.require(actor,tenant,InventoryAdministrationAuthorization.Action.POLICY_MANAGE);
        requireEvidence(actor,tenant,reason,correlation);
        java.util.Objects.requireNonNull(variant,"variant");
        if(expected<0 || desired<0) throw new IllegalArgumentException("Safety stock must be nonnegative");
        return transactions.execute(()-> {
            var current=repository.lockPosition(tenant,variant).orElseThrow(()->new InventoryAdministrationException(Reason.TARGET_UNAVAILABLE));
            if(current.safetyStock()==desired) return current;
            if(current.safetyStock()!=expected) throw new InventoryAdministrationException(Reason.CONFLICT);
            repository.saveSafety(tenant,variant,desired);
            repository.appendSafety(actor,tenant,variant,expected,desired,reason,correlation,time.now());
            return InventoryPosition.create(tenant,variant,current.onHand(),current.committed(),current.backordered(),desired);
        });
    }
    /** Keeps forensic metadata structurally bounded before any mutation begins. */
    private static void requireEvidence(UUID actor,UUID tenant,String reason,UUID correlation) {
        java.util.Objects.requireNonNull(actor,"actor"); java.util.Objects.requireNonNull(tenant,"tenant");
        java.util.Objects.requireNonNull(correlation,"correlation");
        if(reason==null || !reason.matches("[A-Z][A-Z0-9_]{0,63}")) throw new IllegalArgumentException("Invalid policy reason");
    }
}
