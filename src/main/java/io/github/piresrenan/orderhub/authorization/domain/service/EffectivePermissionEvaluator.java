package io.github.piresrenan.orderhub.authorization.domain.service;

import java.util.Collection;

import io.github.piresrenan.orderhub.authorization.domain.model.AuthorizationDecision;
import io.github.piresrenan.orderhub.authorization.domain.model.AuthorizationPersona;
import io.github.piresrenan.orderhub.authorization.domain.model.PermissionCode;
import io.github.piresrenan.orderhub.authorization.domain.model.PermissionEffect;
import io.github.piresrenan.orderhub.authorization.domain.model.PermissionEnvelope;
import io.github.piresrenan.orderhub.authorization.domain.model.PermissionOverride;
import io.github.piresrenan.orderhub.authorization.domain.model.RoleDefinition;

/**
 * Deterministically resolves one permission against role state, bounded direct
 * overrides and the actor's effective permission envelope.
 *
 * <p>
 * This evaluator deliberately grants nothing based only on organizational
 * authority rank. Effective permissions remain explicit.
 * </p>
 */
public final class EffectivePermissionEvaluator {

    public AuthorizationDecision evaluate(
            RoleDefinition role,
            PermissionEnvelope actorEnvelope,
            Collection<PermissionOverride> overrides,
            PermissionCode requestedPermission) {

        if (role == null) {
            throw new IllegalArgumentException(
                    "Role definition is required");
        }

        if (actorEnvelope == null) {
            throw new IllegalArgumentException(
                    "Actor permission envelope is required");
        }

        if (overrides == null) {
            throw new IllegalArgumentException(
                    "Permission overrides are required");
        }

        if (requestedPermission == null) {
            throw new IllegalArgumentException(
                    "Requested permission is required");
        }

        if (role.persona()
                != AuthorizationPersona.STAFF
                || !requestedPermission
                        .supports(
                                role.persona())) {

            return AuthorizationDecision.DENY;
        }

        var explicitlyDenied =
                overrides.stream()
                        .anyMatch(override ->
                                override.permission()
                                        == requestedPermission
                                        && override.effect()
                                                == PermissionEffect.DENY);

        if (explicitlyDenied) {
            return AuthorizationDecision.DENY;
        }

        /*
         * The actor envelope is authoritative even if persisted/configured role
         * or override state is broader than expected.
         */
        if (!actorEnvelope
                .allows(
                        requestedPermission)) {

            return AuthorizationDecision.DENY;
        }

        if (role.permissions()
                .contains(
                        requestedPermission)) {

            return AuthorizationDecision.ALLOW;
        }

        var explicitlyAllowed =
                overrides.stream()
                        .anyMatch(override ->
                                override.permission()
                                        == requestedPermission
                                        && override.effect()
                                                == PermissionEffect.ALLOW);

        if (explicitlyAllowed) {
            return AuthorizationDecision.ALLOW;
        }

        return AuthorizationDecision.DENY;
    }
}
