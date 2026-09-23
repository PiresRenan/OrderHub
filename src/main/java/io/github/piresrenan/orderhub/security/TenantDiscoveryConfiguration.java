package io.github.piresrenan.orderhub.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.github.piresrenan.orderhub.security.application.port.in.DiscoverSelectableTenantsUseCase;
import io.github.piresrenan.orderhub.security.application.service.DiscoverSelectableTenantsService;
import io.github.piresrenan.orderhub.tenants.application.port.in.operational.FindActiveTenantSummariesUseCase;
import io.github.piresrenan.orderhub.users.application.port.in.ScanOperationallyActiveMembershipTenantsUseCase;

/**
 * Composes self-scoped Tenant discovery (ADR-0021) separately from the
 * authentication and trusted-Tenant-context composition in
 * {@link SecurityConfiguration}, so the security boundary itself does not
 * depend on discovery.
 */
@Configuration(proxyBeanMethods = false)
public class TenantDiscoveryConfiguration {

    /**
     * Composes discovery from the Users and Tenants owner answers. The result is
     * presentation context only and never produces Tenant authority.
     *
     * @param memberships Users-owned bounded membership scan boundary
     * @param tenants Tenants-owned bounded ACTIVE summary boundary
     * @return self-scoped Tenant discovery use case
     */
    @Bean
    DiscoverSelectableTenantsUseCase discoverSelectableTenantsUseCase(
            ScanOperationallyActiveMembershipTenantsUseCase memberships,
            FindActiveTenantSummariesUseCase tenants) {

        return new DiscoverSelectableTenantsService(
                memberships,
                tenants);
    }
}
