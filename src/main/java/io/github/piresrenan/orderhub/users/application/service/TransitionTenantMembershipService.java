package io.github.piresrenan.orderhub.users.application.service;

import java.util.Objects;
import java.util.UUID;
import io.github.piresrenan.orderhub.users.application.port.in.TransitionTenantMembershipUseCase;
import io.github.piresrenan.orderhub.users.application.port.in.TenantMembershipTransitionUnavailableException;
import io.github.piresrenan.orderhub.users.application.port.out.TenantMembershipTransitionRepository;
import io.github.piresrenan.orderhub.users.domain.model.TenantMembershipStatus;

/** Explicit recovery is limited to suspension; termination preserves history and is terminal. */
public final class TransitionTenantMembershipService implements TransitionTenantMembershipUseCase {
    private final TenantMembershipTransitionRepository repository;
    public TransitionTenantMembershipService(TenantMembershipTransitionRepository repository) { this.repository = Objects.requireNonNull(repository); }

    @Override public boolean transition(UUID actor, UUID tenant, UUID subject, Action action, UUID correlation) {
        Objects.requireNonNull(actor); Objects.requireNonNull(tenant); Objects.requireNonNull(subject);
        Objects.requireNonNull(action); Objects.requireNonNull(correlation);
        var before = repository.lock(tenant, subject).orElseThrow(TenantMembershipTransitionUnavailableException::new);
        var after = switch (action) {
            case SUSPEND -> TenantMembershipStatus.SUSPENDED;
            case RECOVER -> TenantMembershipStatus.ACTIVE;
            case TERMINATE -> TenantMembershipStatus.TERMINATED;
        };
        if (before == after) { return false; }
        if (before == TenantMembershipStatus.TERMINATED) { throw new TenantMembershipTransitionUnavailableException(); }
        repository.change(actor, tenant, subject, action.name(), before, after, correlation);
        return true;
    }
}
