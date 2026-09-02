package io.github.piresrenan.orderhub.authorization.domain.model;

import java.util.UUID;

/**
 * Framework-neutral Tenant-scoped permission decision request.
 */
public record TenantAuthorizationRequest(
        UUID userId,
        AuthorizationPersona persona,
        TenantAuthorizationScope scope,
        PermissionCode permission) {

    public TenantAuthorizationRequest {

        if (userId == null) {
            throw new IllegalArgumentException(
                    "Authorization request user id is required");
        }

        if (persona == null) {
            throw new IllegalArgumentException(
                    "Authorization request persona is required");
        }

        if (scope == null) {
            throw new IllegalArgumentException(
                    "Authorization request scope is required");
        }

        if (permission == null) {
            throw new IllegalArgumentException(
                    "Authorization request permission is required");
        }
    }
}
