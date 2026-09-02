package io.github.piresrenan.orderhub.authorization.domain.constraint;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import io.github.piresrenan.orderhub.authorization.domain.model.RoleAssignment;
import io.github.piresrenan.orderhub.authorization.domain.model.RoleDefinition;
import io.github.piresrenan.orderhub.authorization.domain.model.TenantAuthorizationRequest;

/**
 * Immutable policy input available to framework-neutral authorization
 * constraints.
 */
public record AuthorizationConstraintContext(
        TenantAuthorizationRequest request,
        List<RoleAssignment> assignments,
        Map<String, RoleDefinition> roleDefinitions) {

    public AuthorizationConstraintContext(
            TenantAuthorizationRequest request,
            Collection<RoleAssignment> assignments,
            Map<String, RoleDefinition> roleDefinitions) {

        this(
                requireRequest(
                        request),
                copyAssignments(
                        assignments),
                copyDefinitions(
                        roleDefinitions));
    }

    private static TenantAuthorizationRequest requireRequest(
            TenantAuthorizationRequest request) {

        if (request == null) {
            throw new IllegalArgumentException(
                    "Constraint authorization request is required");
        }

        return request;
    }

    private static List<RoleAssignment> copyAssignments(
            Collection<RoleAssignment> assignments) {

        if (assignments == null) {
            throw new IllegalArgumentException(
                    "Constraint role assignments are required");
        }

        if (assignments.stream()
                .anyMatch(assignment ->
                        assignment == null)) {

            throw new IllegalArgumentException(
                    "Constraint role assignments cannot contain null");
        }

        return List.copyOf(
                assignments);
    }

    private static Map<String, RoleDefinition> copyDefinitions(
            Map<String, RoleDefinition> roleDefinitions) {

        if (roleDefinitions == null) {
            throw new IllegalArgumentException(
                    "Constraint role definitions are required");
        }

        if (roleDefinitions.entrySet()
                .stream()
                .anyMatch(entry ->
                        entry.getKey() == null
                                || entry.getValue() == null)) {

            throw new IllegalArgumentException(
                    "Constraint role definitions cannot contain null");
        }

        return Map.copyOf(
                roleDefinitions);
    }
}
