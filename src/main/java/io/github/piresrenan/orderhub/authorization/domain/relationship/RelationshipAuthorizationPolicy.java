package io.github.piresrenan.orderhub.authorization.domain.relationship;

import io.github.piresrenan.orderhub.authorization.domain.model.AuthorizationDecision;

/**
 * Framework-neutral policy hook for authorization decisions based on
 * resource/subject relationship facts.
 */
@FunctionalInterface
public interface RelationshipAuthorizationPolicy {

    AuthorizationDecision evaluate(
            RelationshipAuthorizationContext context);
}
