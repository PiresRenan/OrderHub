package io.github.piresrenan.orderhub.authorization.domain.service;

import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

import io.github.piresrenan.orderhub.authorization.domain.constraint.AuthorizationConstraint;
import io.github.piresrenan.orderhub.authorization.domain.constraint.AuthorizationConstraintContext;
import io.github.piresrenan.orderhub.authorization.domain.model.AuthorizationDecision;
import io.github.piresrenan.orderhub.authorization.domain.model.AuthorizationPersona;
import io.github.piresrenan.orderhub.authorization.domain.model.PermissionCode;
import io.github.piresrenan.orderhub.authorization.domain.model.PermissionEnvelope;
import io.github.piresrenan.orderhub.authorization.domain.model.RoleAssignment;
import io.github.piresrenan.orderhub.authorization.domain.model.RoleDefinition;
import io.github.piresrenan.orderhub.authorization.domain.model.TenantAuthorizationRequest;
import io.github.piresrenan.orderhub.authorization.domain.model.UserPermissionOverride;

/**
 * Enforces subject, Tenant and policy isolation before resolving effective
 * permissions.
 */
public final class ScopedAuthorizationEvaluator {

    private final EffectivePermissionEvaluator permissionEvaluator;

    public ScopedAuthorizationEvaluator() {

        this(
                new EffectivePermissionEvaluator());
    }

    ScopedAuthorizationEvaluator(
            EffectivePermissionEvaluator permissionEvaluator) {

        if (permissionEvaluator == null) {
            throw new IllegalArgumentException(
                    "Effective permission evaluator is required");
        }

        this.permissionEvaluator =
                permissionEvaluator;
    }

    public AuthorizationDecision evaluate(
            TenantAuthorizationRequest request,
            Collection<RoleAssignment> assignments,
            Map<String, RoleDefinition> roleDefinitions,
            PermissionEnvelope actorEnvelope,
            Collection<UserPermissionOverride> userOverrides) {

        return evaluate(
                request,
                assignments,
                roleDefinitions,
                actorEnvelope,
                userOverrides,
                List.of());
    }

    public AuthorizationDecision evaluate(
            TenantAuthorizationRequest request,
            Collection<RoleAssignment> assignments,
            Map<String, RoleDefinition> roleDefinitions,
            PermissionEnvelope actorEnvelope,
            Collection<UserPermissionOverride> userOverrides,
            Collection<AuthorizationConstraint> constraints) {

        if (request == null) {
            throw new IllegalArgumentException(
                    "Authorization request is required");
        }

        if (assignments == null) {
            throw new IllegalArgumentException(
                    "Role assignments are required");
        }

        if (roleDefinitions == null) {
            throw new IllegalArgumentException(
                    "Role definitions are required");
        }

        if (actorEnvelope == null) {
            throw new IllegalArgumentException(
                    "Actor permission envelope is required");
        }

        if (userOverrides == null) {
            throw new IllegalArgumentException(
                    "User permission overrides are required");
        }

        if (constraints == null) {
            throw new IllegalArgumentException(
                    "Authorization constraints are required");
        }

        if (request.persona()
                != AuthorizationPersona.STAFF) {

            return AuthorizationDecision.DENY;
        }

        if (assignments.stream()
                .anyMatch(assignment ->
                        assignment == null)
                || userOverrides.stream()
                        .anyMatch(override ->
                                override == null)
                || roleDefinitions.entrySet()
                        .stream()
                        .anyMatch(entry ->
                                entry.getKey() == null
                                        || entry.getValue() == null)
                || constraints.stream()
                        .anyMatch(constraint ->
                                constraint == null)) {

            return AuthorizationDecision.DENY;
        }

        var constraintContext =
                new AuthorizationConstraintContext(
                        request,
                        assignments,
                        roleDefinitions);

        try {
            for (var constraint : constraints) {

                if (constraint.evaluate(
                        constraintContext)
                        != AuthorizationDecision.ALLOW) {

                    return AuthorizationDecision.DENY;
                }
            }

        } catch (RuntimeException exception) {

            /*
             * Authorization constraint evaluation is restrictive policy.
             * Unavailable or inconsistent policy must never fail open.
             */
            return AuthorizationDecision.DENY;
        }

        var rolePermissions =
                EnumSet.noneOf(
                        PermissionCode.class);

        for (var assignment : assignments) {

            if (!assignment.appliesTo(
                    request.userId(),
                    request.persona(),
                    request.scope())) {

                continue;
            }

            var role =
                    roleDefinitions.get(
                            assignment.roleCode());

            if (role == null
                    || !assignment.roleCode()
                            .equals(
                                    role.code())
                    || role.persona()
                            != request.persona()) {

                return AuthorizationDecision.DENY;
            }

            rolePermissions.addAll(
                    role.permissions());
        }

        var scopedOverrides =
                userOverrides.stream()
                        .filter(override ->
                                override.appliesTo(
                                        request.userId(),
                                        request.scope()))
                        .map(
                                UserPermissionOverride::override)
                        .toList();

        return permissionEvaluator.evaluate(
                request.persona(),
                rolePermissions,
                actorEnvelope,
                scopedOverrides,
                request.permission());
    }
}
