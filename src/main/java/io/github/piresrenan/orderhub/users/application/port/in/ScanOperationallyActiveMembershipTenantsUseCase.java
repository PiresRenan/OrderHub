package io.github.piresrenan.orderhub.users.application.port.in;

/**
 * Lists, in bounded windows, the Tenant identifiers of one User's operationally
 * active memberships.
 */
public interface ScanOperationallyActiveMembershipTenantsUseCase {

    /**
     * Reads exactly one bounded window.
     *
     * @param query User, exclusive cursor and window size
     * @return scanned identifiers and whether more remain
     * @throws TenantMembershipScanUnavailableException when persistence cannot answer
     */
    OperationallyActiveMembershipTenantScan scan(
            ScanOperationallyActiveMembershipTenantsQuery query);
}
