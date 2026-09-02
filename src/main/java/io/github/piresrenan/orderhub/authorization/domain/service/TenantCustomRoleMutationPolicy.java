package io.github.piresrenan.orderhub.authorization.domain.service;

import io.github.piresrenan.orderhub.authorization.domain.model.AuthorizationDecision;
import io.github.piresrenan.orderhub.authorization.domain.model.PermissionEnvelope;
import io.github.piresrenan.orderhub.authorization.domain.model.RoleDefinition;
import io.github.piresrenan.orderhub.authorization.domain.model.RoleMutability;

/**
 * Restricts the ordinary Tenant custom-role definition mutation path.
 *
 * <p>
 * Canonical system, protected governance and built-in functional definitions
 * are never edited in place through this policy. A Tenant custom role may only
 * evolve inside its existing definition envelope and the acting administrator's
 * delegation envelope.
 * </p>
 */
public final class TenantCustomRoleMutationPolicy {

    /**
     * Evaluates a proposed replacement for one existing Tenant custom role.
     *
     * <p>
     * Stable role identity, persona and authority band cannot be rewritten by
     * ordinary permission customization. The replacement also cannot widen the
     * role's existing permission envelope.
     * </p>
     *
     * @param currentDefinition currently durable role definition
     * @param replacementDefinition proposed replacement definition
     * @param actorDelegationEnvelope maximum permissions the actor may delegate
     * @return ALLOW only for a bounded mutation of an existing TENANT_CUSTOM role
     */
    public AuthorizationDecision evaluate(
            RoleDefinition currentDefinition,
            RoleDefinition replacementDefinition,
            PermissionEnvelope actorDelegationEnvelope) {

        if (currentDefinition == null) {
            throw new IllegalArgumentException(
                    "Current role definition is required");
        }

        if (replacementDefinition == null) {
            throw new IllegalArgumentException(
                    "Replacement role definition is required");
        }

        if (actorDelegationEnvelope == null) {
            throw new IllegalArgumentException(
                    "Actor delegation envelope is required");
        }

        if (currentDefinition.mutability()
                != RoleMutability.TENANT_CUSTOM
                || replacementDefinition.mutability()
                        != RoleMutability.TENANT_CUSTOM) {

            return AuthorizationDecision.DENY;
        }

        if (!currentDefinition.code()
                .equals(
                        replacementDefinition.code())
                || currentDefinition.persona()
                        != replacementDefinition.persona()
                || currentDefinition.authorityBand()
                        != replacementDefinition.authorityBand()) {

            return AuthorizationDecision.DENY;
        }

        if (!currentDefinition.permissionEnvelope()
                .containsAll(
                        replacementDefinition
                                .permissionEnvelope()
                                .permissions())) {

            return AuthorizationDecision.DENY;
        }

        if (!currentDefinition.permissionEnvelope()
                .containsAll(
                        replacementDefinition.permissions())) {

            return AuthorizationDecision.DENY;
        }

        if (!actorDelegationEnvelope.containsAll(
                replacementDefinition
                        .permissionEnvelope()
                        .permissions())) {

            return AuthorizationDecision.DENY;
        }

        if (!actorDelegationEnvelope.containsAll(
                replacementDefinition.permissions())) {

            return AuthorizationDecision.DENY;
        }

        return AuthorizationDecision.ALLOW;
    }
}