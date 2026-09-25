package io.github.piresrenan.orderhub.users.application.port.in;

/**
 * Establishes a brand-new internal User and its exact external identity
 * binding, refusing to adopt a User that the pair is already bound to.
 *
 * <p>This is the exclusive counterpart of
 * {@link ResolveOrCreateExternalUserUseCase}. It runs in the same serialized
 * provisioning scope and joins an enclosing transaction, so a caller that must
 * never elevate a pre-existing User can compose it atomically with its own
 * work.</p>
 */
public interface EstablishNewExternalUserUseCase {

    /**
     * Creates one internal User and binds the exact pair to it.
     *
     * @param query exact external identity pair
     * @return the newly created internal User identity
     * @throws ExternalIdentityAlreadyBoundException when the exact pair is
     *                                               already bound to any User
     */
    ResolvedUserIdentity establishNew(
            ResolveExternalIdentityQuery query);
}
