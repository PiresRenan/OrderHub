package io.github.piresrenan.orderhub.users.application.port.out;

import java.util.function.Supplier;

/**
 * Coordinates the atomic establishment of an internal User for one exact
 * external identity.
 *
 * <p>The supplied work must execute inside one persistence transaction. When
 * an ambient transaction already exists, the coordination scope participates
 * in that transaction rather than committing independently.</p>
 *
 * <p>Concurrent scopes for the same exact issuer/subject pair must be
 * serialized before the supplied work begins. Consequently callers can inspect
 * durable binding state and, only when absent, create a User without competing
 * same-identity scopes creating speculative losing Users.</p>
 *
 * <p>If the supplied work fails, persistence performed by that work must not
 * commit independently of the enclosing coordination scope.</p>
 */
public interface ExternalIdentityUserProvisioningCoordinator {

    /**
     * Executes one serialized external-identity provisioning scope.
     *
     * @param issuer exact external identity issuer
     * @param subject exact external identity subject
     * @param work application work executed after same-pair serialization
     * @param <T> result type
     * @return work result
     */
    <T> T executeSerialized(
            String issuer,
            String subject,
            Supplier<T> work);
}
