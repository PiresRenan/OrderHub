package io.github.piresrenan.orderhub.security.application.port.in;

/**
 * Discovers the Tenant contexts the authenticated User may currently select.
 *
 * <p>Discovery is presentation context only. It never grants Tenant authority
 * and never replaces trusted Tenant context resolution.
 */
public interface DiscoverSelectableTenantsUseCase {

    /**
     * Returns one bounded scan window.
     *
     * @param query principal, cursor and limit
     * @return selectable Tenants and continuation cursor
     */
    SelectableTenantPage discover(
            DiscoverSelectableTenantsQuery query);
}
