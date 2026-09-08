package io.github.piresrenan.orderhub.catalog.application.port.in.administration;
/** Bounded authoring input; lifecycle and associations have separate commands. */
public record CatalogProductMetadata(String name, String slug, String description, String brand) {
    /** Bounds request data while leaving commercial semantics to the existing Product model. */
    public CatalogProductMetadata {
        if (name == null || name.codePointCount(0, name.length()) > 160
                || (description != null && description.codePointCount(0, description.length()) > 4000))
            throw new IllegalArgumentException("Invalid Product metadata");
    }
}
