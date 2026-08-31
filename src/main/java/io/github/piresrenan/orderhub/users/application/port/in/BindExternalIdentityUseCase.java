package io.github.piresrenan.orderhub.users.application.port.in;

import io.github.piresrenan.orderhub.users.domain.model.ExternalIdentityBinding;

/**
 * Defines the application boundary for associating external provider identity
 * with an internal OrderHub User.
 */
public interface BindExternalIdentityUseCase {

    /**
     * Establishes one durable external identity association.
     *
     * @param command complete external/internal identity association request
     * @return successfully created and persisted binding
     * @throws IllegalArgumentException when the requested binding violates its
     *                                  domain invariants
     */
    ExternalIdentityBinding bind(
            BindExternalIdentityCommand command);
}
