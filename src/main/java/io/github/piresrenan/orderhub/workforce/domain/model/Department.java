package io.github.piresrenan.orderhub.workforce.domain.model;

import java.util.UUID;

/**
 * Configurable Tenant-scoped organizational grouping.
 *
 * <p>
 * Department is organizational context only. It is not a RoleDefinition and
 * grants no executable permission.
 * </p>
 */
public record Department(
        UUID departmentId,
        UUID tenantId,
        String code,
        String name) {

    public Department {

        if (departmentId == null) {
            throw new IllegalArgumentException(
                    "Department ID is required");
        }

        if (tenantId == null) {
            throw new IllegalArgumentException(
                    "Tenant ID is required");
        }

        code =
                requireText(
                        code,
                        "Department code");

        name =
                requireText(
                        name,
                        "Department name");
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
