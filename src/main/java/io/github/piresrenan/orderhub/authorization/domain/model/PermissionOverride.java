package io.github.piresrenan.orderhub.authorization.domain.model;

/**
 * Exceptional account-specific permission adjustment.
 *
 * <p>
 * DENY may always reduce authority. ALLOW is accepted only when the permission
 * belongs to the applicable permission envelope.
 * </p>
 */
public record PermissionOverride(
        PermissionCode permission,
        PermissionEffect effect) {

    public PermissionOverride {

        if (permission == null) {
            throw new IllegalArgumentException(
                    "Override permission is required");
        }

        if (effect == null) {
            throw new IllegalArgumentException(
                    "Override effect is required");
        }
    }

    public static PermissionOverride allow(
            PermissionCode permission,
            PermissionEnvelope envelope) {

        if (envelope == null) {
            throw new IllegalArgumentException(
                    "Permission envelope is required");
        }

        if (!envelope.allows(
                permission)) {

            throw new IllegalArgumentException(
                    "ALLOW override exceeds the permission envelope");
        }

        return new PermissionOverride(
                permission,
                PermissionEffect.ALLOW);
    }

    public static PermissionOverride deny(
            PermissionCode permission) {

        return new PermissionOverride(
                permission,
                PermissionEffect.DENY);
    }
}
