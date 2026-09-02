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
 * Deterministically resolves one effective permission.
 *
 * <p>
 * Organizational rank never manufactures a permission. The result depends on
 * explicit role permissions, bounded account overrides and the effective
 * permission envelope.
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

        return evaluate(
                role.persona(),
                role.permissions(),
                actorEnvelope,
                overrides,
                requestedPermission);
    }

    public AuthorizationDecision evaluate(
            AuthorizationPersona persona,
            Collection<PermissionCode> rolePermissions,
            PermissionEnvelope actorEnvelope,
            Collection<PermissionOverride> overrides,
            PermissionCode requestedPermission) {

        if (persona == null) {
            throw new IllegalArgumentException(
                    "Authorization persona is required");
        }

        if (rolePermissions == null) {
            throw new IllegalArgumentException(
                    "Role permissions are required");
        }

        if (rolePermissions.stream()
                .anyMatch(permission ->
                        permission == null)) {

            throw new IllegalArgumentException(
                    "Role permissions cannot contain null");
        }

        if (actorEnvelope == null) {
            throw new IllegalArgumentException(
                    "Actor permission envelope is required");
        }

        if (overrides == null) {
            throw new IllegalArgumentException(
                    "Permission overrides are required");
        }

        if (overrides.stream()
                .anyMatch(override ->
                        override == null)) {

            throw new IllegalArgumentException(
                    "Permission overrides cannot contain null");
        }

        if (requestedPermission == null) {
            throw new IllegalArgumentException(
                    "Requested permission is required");
        }

        if (persona
                != AuthorizationPersona.STAFF
                || !requestedPermission
                        .supports(
                                persona)) {

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
         * The current organizational envelope is authoritative even if
         * persisted/configured authorization state is broader.
         */
        if (!actorEnvelope
                .allows(
                        requestedPermission)) {

            return AuthorizationDecision.DENY;
        }

        if (rolePermissions.contains(
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
