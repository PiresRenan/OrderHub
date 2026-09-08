package io.github.piresrenan.orderhub.inventory.application.port.out;

import java.util.UUID;

/** Resolves current Tenant Staff authority before any target or retry lookup. */
@FunctionalInterface
public interface InventoryAdministrationAuthorization {
    enum Action { VIEW, RECEIVE, ADJUST, POLICY_MANAGE }
    /** Rejects absent current Staff permission before any target or operation-identity lookup. */
    void require(UUID actorUserId, UUID tenantId, Action action);
}
