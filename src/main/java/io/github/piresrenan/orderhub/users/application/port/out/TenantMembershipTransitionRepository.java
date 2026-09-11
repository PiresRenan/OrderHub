package io.github.piresrenan.orderhub.users.application.port.out;

import java.util.Optional;
import java.util.UUID;
import io.github.piresrenan.orderhub.users.domain.model.TenantMembershipStatus;

public interface TenantMembershipTransitionRepository {
    /** Locks the exact membership before evaluating a lifecycle transition, including terminal-state retries. */
    Optional<TenantMembershipStatus> lock(UUID tenant, UUID subject);
    /** Changes the locked membership and appends its evidence in one physical transaction. */
    void change(UUID actor, UUID tenant, UUID subject, String action, TenantMembershipStatus before, TenantMembershipStatus after, UUID correlation);
}
