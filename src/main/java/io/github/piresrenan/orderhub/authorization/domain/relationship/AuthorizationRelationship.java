package io.github.piresrenan.orderhub.authorization.domain.relationship;

/**
 * Bounded relationship vocabulary supplied by resource-owning modules to
 * contextual authorization policy.
 *
 * <p>
 * Authorization does not infer these relationships from foreign persistence.
 * Future resource-owning modules prove the relationship and supply only the
 * policy fact required by the decision.
 * </p>
 */
public enum AuthorizationRelationship {

    RESOURCE_OWNER
}
