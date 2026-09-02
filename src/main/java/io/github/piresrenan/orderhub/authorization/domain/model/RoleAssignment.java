package io.github.piresrenan.orderhub.authorization.domain.model;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Assigns one role definition to one internal User in one explicit Tenant scope.
 *
 * <p>
 * RoleAssignment is authorization state. It is deliberately separate from
 * User identity and TenantMembership.
 * </p>
 */
public record RoleAssignment(
        UUID userId,
        AuthorizationPersona persona,
        TenantAuthorizationScope scope,
        String roleCode) {

    private static final Pattern ROLE_CODE_PATTERN =
            Pattern.compile(
                    "[A-Z][A-Z0-9_]{2,63}");

    public RoleAssignment {

        if (userId == null) {
            throw new IllegalArgumentException(
                    "Role assignment user id is required");
        }

        if (persona == null) {
            throw new IllegalArgumentException(
                    "Role assignment persona is required");
        }

        if (persona
                != AuthorizationPersona.STAFF) {

            throw new IllegalArgumentException(
                    "Role assignments require the STAFF persona");
        }

        if (scope == null) {
            throw new IllegalArgumentException(
                    "Role assignment scope is required");
        }

        if (roleCode == null
                || !ROLE_CODE_PATTERN
                        .matcher(
                                roleCode)
                        .matches()) {

            throw new IllegalArgumentException(
                    "Role assignment code must use stable upper snake case");
        }
    }

    public boolean appliesTo(
            UUID requestedUserId,
            AuthorizationPersona requestedPersona,
            TenantAuthorizationScope requestedScope) {

        return userId.equals(
                requestedUserId)
                && persona
                        == requestedPersona
                && scope.equals(
                        requestedScope);
    }
}
