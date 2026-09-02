package io.github.piresrenan.orderhub.authorization.domain.relationship;

import java.util.Set;
import java.util.UUID;

import io.github.piresrenan.orderhub.authorization.domain.model.AuthorizationPersona;
import io.github.piresrenan.orderhub.authorization.domain.model.TenantAuthorizationScope;

/**
 * Framework-neutral relationship/context policy input.
 *
 * <p>
 * Resource identity and resource-domain objects deliberately do not enter this
 * model. The owning module resolves the applicable relationship facts before
 * invoking authorization.
 * </p>
 */
public record RelationshipAuthorizationContext(
        UUID actorUserId,
        AuthorizationPersona persona,
        TenantAuthorizationScope scope,
        Set<AuthorizationRelationship> relationships) {

    public RelationshipAuthorizationContext {

        if (actorUserId == null) {
            throw new IllegalArgumentException(
                    "Relationship actor user id is required");
        }

        if (persona == null) {
            throw new IllegalArgumentException(
                    "Relationship persona is required");
        }

        if (scope == null) {
            throw new IllegalArgumentException(
                    "Relationship authorization scope is required");
        }

        if (relationships == null
                || relationships.stream()
                        .anyMatch(relationship ->
                                relationship == null)) {

            throw new IllegalArgumentException(
                    "Authorization relationships are required");
        }

        relationships =
                Set.copyOf(
                        relationships);
    }
}
