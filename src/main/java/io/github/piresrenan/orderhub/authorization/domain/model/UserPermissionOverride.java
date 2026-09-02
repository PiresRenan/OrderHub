package io.github.piresrenan.orderhub.authorization.domain.model;

import java.util.UUID;

/**
 * Account-specific permission customization bound to one internal User and one
 * explicit Tenant scope.
 *
 * <p>
 * The nested PermissionOverride remains the pure ALLOW/DENY policy effect.
 * This type supplies the subject/scope ownership required by durable
 * authorization state.
 * </p>
 */
public record UserPermissionOverride(
        UUID userId,
        TenantAuthorizationScope scope,
        PermissionOverride override) {

    public UserPermissionOverride {

        if (userId == null) {
            throw new IllegalArgumentException(
                    "Permission override user id is required");
        }

        if (scope == null) {
            throw new IllegalArgumentException(
                    "Permission override scope is required");
        }

        if (override == null) {
            throw new IllegalArgumentException(
                    "Permission override is required");
        }
    }

    public boolean appliesTo(
            UUID requestedUserId,
            TenantAuthorizationScope requestedScope) {

        return userId.equals(
                requestedUserId)
                && scope.equals(
                        requestedScope);
    }
}
