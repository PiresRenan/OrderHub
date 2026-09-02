package io.github.piresrenan.orderhub.authorization.domain.service;

import java.util.Collection;
import java.util.EnumSet;
import java.util.Map;

import io.github.piresrenan.orderhub.authorization.domain.model.AuthorizationDecision;
import io.github.piresrenan.orderhub.authorization.domain.model.AuthorizationPersona;
import io.github.piresrenan.orderhub.authorization.domain.model.PermissionCode;
import io.github.piresrenan.orderhub.authorization.domain.model.PermissionEnvelope;
import io.github.piresrenan.orderhub.authorization.domain.model.RoleAssignment;
import io.github.piresrenan.orderhub.authorization.domain.model.RoleDefinition;
import io.github.piresrenan.orderhub.authorization.domain.model.TenantAuthorizationRequest;
import io.github.piresrenan.orderhub.authorization.domain.model.UserPermissionOverride;

/**
 * Enforces subject and Tenant isolation before resolving effective permissions.
 *
 * <p>
 * Only RoleAssignments and UserPermissionOverrides belonging to the exact
 * requested User and Tenant participate in the decision.
 * </p>
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

        /*
         * The STAFF RBAC path must never accidentally authorize a Customer.
         * Customer ownership/relationship policy is introduced separately.
         */
        if (request.persona()
                != AuthorizationPersona.STAFF) {

            return AuthorizationDecision.DENY;
        }

        /*
         * Invalid/corrupt policy collections fail closed instead of silently
         * skipping malformed authorization state.
         */
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
                                        || entry.getValue() == null)) {

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

            /*
             * A role assignment referring to missing/inconsistent role state is
             * an authorization-policy inconsistency and therefore fails closed.
             */
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
