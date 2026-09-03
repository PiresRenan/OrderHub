package io.github.piresrenan.orderhub.authorization.domain.model;

import org.springframework.modulith.NamedInterface;

import java.util.Collection;
import java.util.Set;

/**
 * Explicit upper bound for permissions that may become effective.
 *
 * <p>
 * Permission customization is always constrained by an envelope so adding
 * direct permissions cannot silently promote an actor.
 * </p>
 */
@NamedInterface("policy-model")
public final class PermissionEnvelope {

    private final Set<PermissionCode> permissions;

    private PermissionEnvelope(
            Set<PermissionCode> permissions) {

        this.permissions = permissions;
    }

    public static PermissionEnvelope none() {

        return new PermissionEnvelope(
                Set.of());
    }

    public static PermissionEnvelope of(
            Collection<PermissionCode> permissions) {

        if (permissions == null) {
            throw new IllegalArgumentException(
                    "Permission envelope is required");
        }

        if (permissions.stream()
                .anyMatch(permission ->
                        permission == null)) {

            throw new IllegalArgumentException(
                    "Permission envelope cannot contain null");
        }

        return new PermissionEnvelope(
                Set.copyOf(
                        permissions));
    }

    public boolean allows(
            PermissionCode permission) {

        if (permission == null) {
            throw new IllegalArgumentException(
                    "Permission is required");
        }

        return permissions.contains(
                permission);
    }

    public boolean containsAll(
            Collection<PermissionCode> candidatePermissions) {

        if (candidatePermissions == null) {
            throw new IllegalArgumentException(
                    "Candidate permissions are required");
        }

        return permissions.containsAll(
                candidatePermissions);
    }

    public Set<PermissionCode> permissions() {

        return permissions;
    }
}
