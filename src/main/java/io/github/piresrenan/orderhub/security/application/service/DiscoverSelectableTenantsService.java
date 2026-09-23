package io.github.piresrenan.orderhub.security.application.service;

import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

import io.github.piresrenan.orderhub.security.application.port.in.DiscoverSelectableTenantsQuery;
import io.github.piresrenan.orderhub.security.application.port.in.DiscoverSelectableTenantsUseCase;
import io.github.piresrenan.orderhub.security.application.port.in.SelectableTenantPage;
import io.github.piresrenan.orderhub.security.application.port.in.SelectableTenantPage.SelectableTenant;
import io.github.piresrenan.orderhub.tenants.application.port.in.operational.ActiveTenantSummary;
import io.github.piresrenan.orderhub.tenants.application.port.in.operational.FindActiveTenantSummariesQuery;
import io.github.piresrenan.orderhub.tenants.application.port.in.operational.FindActiveTenantSummariesUseCase;
import io.github.piresrenan.orderhub.users.application.port.in.ScanOperationallyActiveMembershipTenantsQuery;
import io.github.piresrenan.orderhub.users.application.port.in.ScanOperationallyActiveMembershipTenantsUseCase;

/**
 * Composes one bounded discovery window from the Users membership answer and
 * the Tenants operational-state answer (ADR-0021).
 *
 * <p>Each call performs exactly one Users scan and at most one Tenants batch
 * read; an empty scan window performs no Tenants read at all. Filtered candidates still advance the cursor, so a window whose Tenants
 * are all suspended yields an empty page with a continuation instead of an
 * extra read loop or an error.
 */
public final class DiscoverSelectableTenantsService
        implements DiscoverSelectableTenantsUseCase {

    private final ScanOperationallyActiveMembershipTenantsUseCase memberships;
    private final FindActiveTenantSummariesUseCase tenants;

    /** Requires the supplied owner contracts; construction performs no read. */
    public DiscoverSelectableTenantsService(
            ScanOperationallyActiveMembershipTenantsUseCase memberships,
            FindActiveTenantSummariesUseCase tenants) {

        if (memberships == null) {
            throw new IllegalArgumentException(
                    "Tenant membership scan boundary is required");
        }

        if (tenants == null) {
            throw new IllegalArgumentException(
                    "Tenant summaries boundary is required");
        }

        this.memberships = memberships;
        this.tenants = tenants;
    }

    /** Continuation is the last scanned membership candidate, never the last returned Tenant. */
    @Override
    public SelectableTenantPage discover(
            DiscoverSelectableTenantsQuery query) {

        var scan = memberships.scan(
                new ScanOperationallyActiveMembershipTenantsQuery(
                        query.authenticatedPrincipal().userId(),
                        query.afterId(),
                        query.limit()));

        if (scan.tenantIds().isEmpty()) {
            return new SelectableTenantPage(List.of(), null);
        }

        var active = tenants.find(
                        new FindActiveTenantSummariesQuery(scan.tenantIds()))
                .stream()
                .collect(Collectors.toMap(ActiveTenantSummary::id, Function.identity()));

        var items = scan.tenantIds().stream()
                .filter(active::containsKey)
                .map(id -> new SelectableTenant(id, active.get(id).name()))
                .toList();

        var next = scan.hasMore()
                ? scan.tenantIds().get(scan.tenantIds().size() - 1)
                : null;

        return new SelectableTenantPage(items, next);
    }
}
