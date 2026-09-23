package io.github.piresrenan.orderhub.tenants.application.port.in.operational;

import java.util.List;

/**
 * Reads, in one bounded batch, the summaries of the supplied Tenants that are
 * currently operationally ACTIVE.
 */
public interface FindActiveTenantSummariesUseCase {

    /**
     * Returns only ACTIVE Tenants among the requested identifiers. Unknown and
     * suspended Tenants are omitted without distinction.
     *
     * @param query bounded identifier batch
     * @return ACTIVE Tenant summaries, in no guaranteed order
     * @throws TenantOperationalStateUnavailableException when persistence cannot answer
     */
    List<ActiveTenantSummary> find(
            FindActiveTenantSummariesQuery query);
}
