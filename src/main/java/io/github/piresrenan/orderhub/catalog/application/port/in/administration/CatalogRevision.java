package io.github.piresrenan.orderhub.catalog.application.port.in.administration;
import java.util.Objects;
/** Owner-local revision accompanies a resource without changing its domain identity. */
public record CatalogRevision<T>(T value, long revision) {
    /** Requires an existing resource and positive revision. */
    public CatalogRevision { Objects.requireNonNull(value, "value"); if (revision < 1) throw new IllegalArgumentException("Invalid revision"); }
}
