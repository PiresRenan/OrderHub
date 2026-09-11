package io.github.piresrenan.orderhub.users.application.port.in;

import java.util.UUID;

/** Owner write boundary for an authorized coordinator; joins its required transaction. */
public interface TransitionTenantMembershipUseCase {
    enum Action { SUSPEND, RECOVER, TERMINATE }
    boolean transition(UUID actor, UUID tenant, UUID subject, Action action, UUID correlation);
}
