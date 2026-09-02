package io.github.piresrenan.orderhub.authorization.domain.relationship;

import io.github.piresrenan.orderhub.authorization.domain.model.AuthorizationDecision;
import io.github.piresrenan.orderhub.authorization.domain.model.AuthorizationPersona;

/**
 * Requires one bounded relationship fact for one explicit authorization persona.
 *
 * <p>
 * This policy is intentionally resource-domain neutral. For example, a future
 * Customers module may prove RESOURCE_OWNER for the authenticated User before
 * invoking a customer-owned resource action.
 * </p>
 */
public record RequiredRelationshipPolicy(
        AuthorizationPersona requiredPersona,
        AuthorizationRelationship requiredRelationship)
        implements RelationshipAuthorizationPolicy {

    public RequiredRelationshipPolicy {

        if (requiredPersona == null) {
            throw new IllegalArgumentException(
                    "Required authorization persona is required");
        }

        if (requiredRelationship == null) {
            throw new IllegalArgumentException(
                    "Required authorization relationship is required");
        }
    }

    @Override
    public AuthorizationDecision evaluate(
            RelationshipAuthorizationContext context) {

        if (context == null) {
            throw new IllegalArgumentException(
                    "Relationship authorization context is required");
        }

        if (context.persona()
                != requiredPersona) {

            return AuthorizationDecision.DENY;
        }

        if (!context.relationships()
                .contains(
                        requiredRelationship)) {

            return AuthorizationDecision.DENY;
        }

        return AuthorizationDecision.ALLOW;
    }
}
