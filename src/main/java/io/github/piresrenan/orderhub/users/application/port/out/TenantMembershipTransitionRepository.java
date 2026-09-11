package io.github.piresrenan.orderhub.users.application.port.out;

import java.util.Optional;
import java.util.UUID;
import io.github.piresrenan.orderhub.users.domain.model.TenantMembershipStatus;

public interface TenantMembershipTransitionRepository {
    Optional<TenantMembershipStatus> lock(UUID tenant, UUID subject);
    void change(UUID actor, UUID tenant, UUID subject, String action, TenantMembershipStatus before, TenantMembershipStatus after, UUID correlation);
}
