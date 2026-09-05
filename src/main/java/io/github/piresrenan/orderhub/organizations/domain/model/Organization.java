package io.github.piresrenan.orderhub.organizations.domain.model;

import java.util.UUID;

public final class Organization {

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

        return new Organization(
                id,
                name.strip(),
                OrganizationStatus.ACTIVE);
    }

    private static void validateId(UUID id) {
        if (id == null) {
            throw new IllegalArgumentException(
                    "Organization id is required");
        }
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
