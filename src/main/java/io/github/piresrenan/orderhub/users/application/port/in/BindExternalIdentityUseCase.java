package io.github.piresrenan.orderhub.users.application.port.in;

/**
 * Defines the application boundary for associating external provider identity
 * with an internal OrderHub User.
 */
public interface BindExternalIdentityUseCase {

    /**
     * Establishes one durable external identity association.
     *
     * <p>
     * Normal completion means the association was persisted. No result is
     * produced because the command already carries every caller-known
     * identity, and the ExternalIdentityBinding aggregate remains internal to
     * Users.
     * </p>
     *
     * @param command complete external/internal identity association request
     * @throws IllegalArgumentException when the requested binding violates its
     *                                  domain invariants
     */
    void bind(
            BindExternalIdentityCommand command);
}
