package io.github.piresrenan.orderhub.organizations.domain.model;

import java.util.UUID;

public final class Organization {

    private static final int MAX_NAME_CODE_POINTS = 120;

    private final UUID id;
    private final String name;
    private final OrganizationStatus status;

    private Organization(
            UUID id,
            String name,
            OrganizationStatus status) {

        this.id = id;
        this.name = name;
        this.status = status;
    }

    public static Organization create(
            UUID id,
            String name) {

        validateId(id);

        var normalizedName =
                normalizeAndValidateName(name);

        return new Organization(
                id,
                normalizedName,
                OrganizationStatus.ACTIVE);
    }

    public static Organization rehydrate(
            UUID id,
            String name,
            OrganizationStatus status) {

        validateId(id);
        validateStatus(status);

        var normalizedName =
                normalizeAndValidateName(name);

        if (!name.equals(normalizedName)) {
            throw new IllegalArgumentException(
                    "Persisted organization name must be normalized");
        }

        return new Organization(
                id,
                name,
                status);
    }

    private static void validateId(UUID id) {
        if (id == null) {
            throw new IllegalArgumentException(
                    "Organization id is required");
        }
    }

    private static void validateStatus(
            OrganizationStatus status) {

        if (status == null) {
            throw new IllegalArgumentException(
                    "Organization status is required");
        }
    }

    private static String normalizeAndValidateName(
            String name) {

        if (name == null) {
            throw new IllegalArgumentException(
                    "Organization name is required");
        }

        var normalizedName =
                name.strip();

        if (normalizedName.isBlank()) {
            throw new IllegalArgumentException(
                    "Organization name must not be blank");
        }

        if (normalizedName.codePointCount(
                0,
                normalizedName.length())
                > MAX_NAME_CODE_POINTS) {

            throw new IllegalArgumentException(
                    "Organization name must not exceed 120 characters");
        }

        return normalizedName;
    }

    public UUID id() {
        return id;
    }

    public String name() {
        return name;
    }

    public OrganizationStatus status() {
        return status;
    }
}
