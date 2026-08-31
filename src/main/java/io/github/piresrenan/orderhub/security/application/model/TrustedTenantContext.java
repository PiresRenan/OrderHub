package io.github.piresrenan.orderhub.security.application.model;

import java.util.UUID;

/**
 * Carries Tenant authority that has already been established by the security
 * application boundary.
 *
 * <p>The context contains only the internal Tenant identifier required by
 * downstream application code. Authentication credentials, external identity,
 * User identity and provider-specific claims are deliberately excluded.
 *
 * @param tenantId trusted internal Tenant identifier
 */
public record TrustedTenantContext(
        UUID tenantId) {

    /**
     * Ensures that trusted Tenant authority is structurally complete.
     *
     * @throws IllegalArgumentException when the Tenant identifier is missing
     */
    public TrustedTenantContext {
        if (tenantId == null) {
            throw new IllegalArgumentException(
                    "Trusted tenant id is required");
        }
    }
}
