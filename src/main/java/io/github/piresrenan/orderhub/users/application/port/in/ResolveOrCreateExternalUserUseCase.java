package io.github.piresrenan.orderhub.users.application.port.in;

/**
 * Resolves one exact verified external identity to an internal OrderHub User,
 * establishing the internal User and durable binding when the identity has not
 * been seen before.
 *
 * <p>The boundary deliberately exposes neither ExternalIdentityBinding nor
 * provider-specific authentication state. Callers receive only the stable
 * internal User identity required for subsequent OrderHub application work.</p>
 */
public interface ResolveOrCreateExternalUserUseCase {

    /**
     * Resolves or establishes the internal User represented by one exact
     * external issuer/subject pair.
     *
     * @param query exact external identity pair
     * @return stable internal User identity associated with the pair
     */
    ResolvedUserIdentity resolveOrCreate(
            ResolveExternalIdentityQuery query);
}
