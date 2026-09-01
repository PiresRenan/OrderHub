package io.github.piresrenan.orderhub.catalog.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Externalized technical bound for Category hierarchy mutations.
 *
 * <p>
 * The timeout is a provisional safety boundary rather than a load-derived SLA.
 * It prevents hierarchy lock waits from remaining unbounded.
 * </p>
 *
 * @param timeout maximum Category hierarchy transaction duration
 */
@ConfigurationProperties(
        prefix = "orderhub.catalog.category-hierarchy.transaction")
public record CatalogCategoryHierarchyTransactionProperties(
        Duration timeout) {

    public CatalogCategoryHierarchyTransactionProperties {

        if (timeout == null) {
            throw new IllegalArgumentException(
                    "Category hierarchy transaction timeout is required");
        }

        if (
            timeout.isZero()
            || timeout.isNegative()
        ) {

            throw new IllegalArgumentException(
                    "Category hierarchy transaction timeout must be greater than zero");
        }

        if (timeout.getNano() != 0) {
            throw new IllegalArgumentException(
                    "Category hierarchy transaction timeout must use whole seconds");
        }

        if (timeout.getSeconds() > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                    "Category hierarchy transaction timeout exceeds supported range");
        }
    }

    public int timeoutSeconds() {

        return Math.toIntExact(
                timeout.getSeconds());
    }
}
