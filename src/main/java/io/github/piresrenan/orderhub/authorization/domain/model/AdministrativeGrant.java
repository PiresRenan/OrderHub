package io.github.piresrenan.orderhub.authorization.domain.model;

import java.util.UUID;

import org.springframework.modulith.NamedInterface;

@NamedInterface("administration")
public record AdministrativeGrant(
        UUID userId,
        AdministrativeScope scope,
        PermissionCode permission) {

    public AdministrativeGrant {

        if (userId == null) {
            throw new IllegalArgumentException(
                    "Administrative grant user id is required");
        }

        if (scope == null) {
            throw new IllegalArgumentException(
                    "Administrative grant scope is required");
        }

        if (permission == null) {
            throw new IllegalArgumentException(
                    "Administrative grant permission is required");
        }

        if (!permission.supportsAdministrativeScope(
                scope.type())) {

            throw new IllegalArgumentException(
                    "Permission is incompatible with administrative scope");
        }
    }
}
