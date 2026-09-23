package io.github.piresrenan.orderhub.users.application.service;

import io.github.piresrenan.orderhub.users.application.port.in.OperationallyActiveMembershipTenantScan;
import io.github.piresrenan.orderhub.users.application.port.in.ScanOperationallyActiveMembershipTenantsQuery;
import io.github.piresrenan.orderhub.users.application.port.in.ScanOperationallyActiveMembershipTenantsUseCase;
import io.github.piresrenan.orderhub.users.application.port.in.TenantMembershipScanUnavailableException;
import io.github.piresrenan.orderhub.users.application.port.out.TenantMembershipPersistenceException;
import io.github.piresrenan.orderhub.users.application.port.out.TenantMembershipScanRepository;

/**
 * Scans one bounded window of operationally active memberships inside Users.
 *
 * <p>
 * One extra row is read to learn whether more memberships remain, so the
 * caller never has to infer the end of the scan from a short window.
 * </p>
 */
public final class ScanOperationallyActiveMembershipTenantsService
        implements ScanOperationallyActiveMembershipTenantsUseCase {

    private final TenantMembershipScanRepository repository;

    /** Requires the Users-owned scan persistence boundary. */
    public ScanOperationallyActiveMembershipTenantsService(
            TenantMembershipScanRepository repository) {

        if (repository == null) {
            throw new IllegalArgumentException(
                    "Tenant membership scan repository is required");
        }

        this.repository = repository;
    }

    /** Performs exactly one bounded persistence read of at most windowSize + 1 rows. */
    @Override
    public OperationallyActiveMembershipTenantScan scan(
            ScanOperationallyActiveMembershipTenantsQuery query) {

        try {
            var rows = repository.findOperationallyActiveTenantIds(
                    query.userId(),
                    query.afterTenantId(),
                    query.windowSize() + 1);

            var hasMore = rows.size() > query.windowSize();

            return new OperationallyActiveMembershipTenantScan(
                    hasMore ? rows.subList(0, query.windowSize()) : rows,
                    hasMore);

        } catch (TenantMembershipPersistenceException exception) {
            throw new TenantMembershipScanUnavailableException(exception);
        }
    }
}
