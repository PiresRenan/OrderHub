package io.github.piresrenan.orderhub.security.application.port.in;

import java.util.List;
import java.util.UUID;

/**
 * One bounded scan window of selectable Tenant contexts.
 *
 * <p>Items may be fewer than the limit, or empty, while more remain. The end of
 * the scan is signaled only by a null {@code nextAfterId}. Nothing here is
 * Tenant authority: every Tenant-scoped request re-establishes trusted context.
 *
 * @param items selectable Tenants in ascending identifier order
 * @param nextAfterId last scanned membership candidate when more remain, otherwise null
 */
public record SelectableTenantPage(
        List<SelectableTenant> items,
        UUID nextAfterId) {

    /** Copies items so the page cannot be mutated after composition. */
    public SelectableTenantPage {
        items = List.copyOf(items);
    }

    /**
     * Minimal Tenant selection projection.
     *
     * @param id Tenant identifier
     * @param name normalized Tenant name
     */
    public record SelectableTenant(UUID id, String name) {
    }
}
