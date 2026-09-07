package io.github.piresrenan.orderhub.tenants.domain.model;

import java.util.UUID;

public final class Tenant {

    private static final int MAX_NAME_CODE_POINTS = 120;

    private final UUID id;
    private final String name;
    private final TenantStatus status;

    private Tenant(
            UUID id,
            String name,
            TenantStatus status) {

        this.id = id;
        this.name = name;
        this.status = status;
    }

    public static Tenant create(
            UUID id,
            String name) {

        validateId(id);

        var normalizedName =
                normalizeAndValidateName(name);

        return new Tenant(
                id,
                normalizedName,
                TenantStatus.ACTIVE);
    }

    public static Tenant rehydrate(
            UUID id,
            String name,
            TenantStatus status) {

        validateId(id);
        validateStatus(status);

        var normalizedName =
                normalizeAndValidateName(name);

        if (!name.equals(normalizedName)) {
            throw new IllegalArgumentException(
                    "Persisted tenant name must be normalized");
        }

        return new Tenant(
                id,
                name,
                status);
    }

    private static void validateId(
            UUID id) {

        if (id == null) {
            throw new IllegalArgumentException(
                    "Tenant id is required");
        }
    }

    private static void validateStatus(
            TenantStatus status) {

        if (status == null) {
            throw new IllegalArgumentException(
                    "Tenant status is required");
        }
    }

    private static String normalizeAndValidateName(
            String name) {

        if (name == null) {
            throw new IllegalArgumentException(
                    "Tenant name is required");
        }

        var normalizedName =
                name.strip();

        if (normalizedName.isBlank()) {
            throw new IllegalArgumentException(
                    "Tenant name must not be blank");
        }

        var codePointCount =
                normalizedName.codePointCount(
                        0,
                        normalizedName.length());

        if (codePointCount > MAX_NAME_CODE_POINTS) {
            throw new IllegalArgumentException(
                    "Tenant name must not exceed 120 characters");
        }

        return normalizedName;
    }

    public Tenant suspend() {

        return new Tenant(
                id,
                name,
                TenantStatus.SUSPENDED);
    }

    public Tenant recover() {

        return new Tenant(
                id,
                name,
                TenantStatus.ACTIVE);
    }

    public UUID id() {
        return id;
    }

    public String name() {
        return name;
    }

    public TenantStatus status() {
        return status;
    }
}
