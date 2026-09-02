package io.github.piresrenan.orderhub.workforce.domain.model;

import java.util.UUID;

import io.github.piresrenan.orderhub.authorization.domain.model.AuthorityBand;
import io.github.piresrenan.orderhub.authorization.domain.model.PermissionEnvelope;

/**
 * Tenant-scoped organizational responsibility and authorization ceiling.
 *
 * <p>
 * A JobPosition is not a RoleDefinition. Its AuthorityBand constrains
 * organizational authority and its PermissionEnvelope is only a maximum
 * permission boundary; neither property automatically grants permissions.
 * </p>
 */
public record JobPosition(
        UUID positionId,
        UUID tenantId,
        String code,
        String title,
        AuthorityBand authorityBand,
        PermissionEnvelope permissionEnvelope) {

    public JobPosition {

        if (positionId == null) {
            throw new IllegalArgumentException(
                    "Position ID is required");
        }

        if (tenantId == null) {
            throw new IllegalArgumentException(
                    "Tenant ID is required");
        }

        code =
                requireText(
                        code,
                        "Position code");

        title =
                requireText(
                        title,
                        "Position title");

        if (authorityBand == null) {
            throw new IllegalArgumentException(
                    "Authority band is required");
        }

        if (permissionEnvelope == null) {
            throw new IllegalArgumentException(
                    "Permission envelope is required");
        }
    }

    private static String requireText(
            String value,
            String label) {

        if (value == null
                || value.isBlank()) {

            throw new IllegalArgumentException(
                    label + " is required");
        }

        return value.trim();
    }
}
