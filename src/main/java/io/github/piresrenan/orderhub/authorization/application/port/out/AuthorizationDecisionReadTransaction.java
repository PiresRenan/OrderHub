package io.github.piresrenan.orderhub.authorization.application.port.out;

import java.util.function.Supplier;

import io.github.piresrenan.orderhub.authorization.domain.model.AuthorizationDecision;

/**
 * Executes one complete authorization decision against one coherent durable
 * read snapshot.
 *
 * <p>
 * The application layer depends on this semantic boundary rather than on Spring
 * transaction APIs or PostgreSQL-specific transaction machinery.
 * </p>
 */
@FunctionalInterface
public interface AuthorizationDecisionReadTransaction {

    AuthorizationDecision execute(
            Supplier<AuthorizationDecision> decision);
}
