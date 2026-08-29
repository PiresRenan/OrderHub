package io.github.piresrenan.orderhub.tenants.domain.model;

import java.util.UUID;

public final class Tenant {

    private static final int MAX_NAME_CODE_POINTS = 120;

    private final UUID id;
    private final String name;

    /**
     * Builds a Tenant from state that has already satisfied the aggregate
     * invariants.
     *
     * @param id immutable Tenant identifier
     * @param name canonical normalized Tenant name
     */
    private Tenant(
            UUID id,
            String name) {

        this.id = id;
        this.name = name;
    }

    /**
     * Creates a new Tenant while normalizing external input and enforcing the
     * invariants required for the aggregate to exist.
     *
     * @param id Tenant identifier
     * @param name human-readable Tenant name supplied by the caller
     * @return a valid Tenant with canonical name representation
     * @throws IllegalArgumentException when an invariant is violated
     */
    public static Tenant create(
            UUID id,
            String name) {

        validateId(id);

        var normalizedName = normalizeAndValidateName(name);

        return new Tenant(
                id,
                normalizedName);
    }

    /**
     * Reconstructs an existing Tenant from durable state.
     *
     * <p>
     * Rehydration validates persisted state but does not silently normalize it.
     * Persistence is expected to contain the canonical representation previously
     * accepted by the domain.
     * </p>
     *
     * @param id persisted Tenant identifier
     * @param name persisted canonical Tenant name
     * @return a valid Tenant reconstructed from persisted state
     * @throws IllegalArgumentException when persisted state violates a domain
     *                                  invariant
     */
    public static Tenant rehydrate(
            UUID id,
            String name) {

        validateId(id);

        var normalizedName = normalizeAndValidateName(name);

        if (!name.equals(normalizedName)) {
            throw new IllegalArgumentException(
                    "Persisted tenant name must be normalized");
        }

        return new Tenant(
                id,
                name);
    }

    /**
     * Validates the aggregate identity shared by creation and reconstruction.
     *
     * @param id Tenant identifier
     * @throws IllegalArgumentException when the identifier is absent
     */
    private static void validateId(UUID id) {
        if (id == null) {
            throw new IllegalArgumentException(
                    "Tenant id is required");
        }
    }

    /**
     * Produces the canonical Tenant name and validates its domain boundaries.
     *
     * <p>
     * Length is measured in Unicode code points rather than UTF-16 code units so
     * non-BMP characters do not consume two logical character positions.
     * </p>
     *
     * @param name Tenant name supplied for validation
     * @return normalized name with surrounding whitespace removed
     * @throws IllegalArgumentException when the name is absent, blank or exceeds
     *                                  the accepted code-point boundary
     */
    private static String normalizeAndValidateName(String name) {
        if (name == null) {
            throw new IllegalArgumentException(
                    "Tenant name is required");
        }

        var normalizedName = name.strip();

        if (normalizedName.isBlank()) {
            throw new IllegalArgumentException(
                    "Tenant name must not be blank");
        }

        var codePointCount = normalizedName.codePointCount(
                0,
                normalizedName.length());

        if (codePointCount > MAX_NAME_CODE_POINTS) {
            throw new IllegalArgumentException(
                    "Tenant name must not exceed 120 characters");
        }

        return normalizedName;
    }

    /**
     * Returns the immutable identity of this Tenant.
     *
     * @return Tenant identifier
     */
    public UUID id() {
        return id;
    }

    /**
     * Returns the canonical normalized Tenant name.
     *
     * @return Tenant name
     */
    public String name() {
        return name;
    }
}
