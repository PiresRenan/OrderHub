package io.github.piresrenan.orderhub.tenants.application.port.in.operational;

import java.util.UUID;

/**
 * Minimal selection projection of one operationally ACTIVE Tenant.
 *
 * @param id Tenant identifier
 * @param name normalized Tenant name (at most 120 code points)
 */
public record ActiveTenantSummary(
        UUID id,
        String name) {
}
