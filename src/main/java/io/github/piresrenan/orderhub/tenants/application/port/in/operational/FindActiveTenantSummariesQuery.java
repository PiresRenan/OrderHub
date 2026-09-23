package io.github.piresrenan.orderhub.tenants.application.port.in.operational;

import java.util.List;
import java.util.UUID;

/**
 * Bounded batch of Tenant identifiers whose ACTIVE summaries are requested.
 *
 * @param tenantIds distinct Tenant identifiers (at most 100)
 */
public record FindActiveTenantSummariesQuery(
        List<UUID> tenantIds) {

    /** Largest batch a single read may request; keeps per-request work bounded. */
    public static final int MAX_BATCH_SIZE = 100;

    /**
     * Rejects missing or unbounded batches before they reach persistence.
     *
     * @throws IllegalArgumentException when the batch is missing, contains null or is too large
     */
    public FindActiveTenantSummariesQuery {
        if (tenantIds == null) {
            throw new IllegalArgumentException(
                    "Tenant ids are required");
        }

        tenantIds = List.copyOf(tenantIds);

        if (tenantIds.size() > MAX_BATCH_SIZE) {
            throw new IllegalArgumentException(
                    "Tenant batch must contain at most 100 ids");
        }
    }
}
