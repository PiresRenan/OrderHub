package io.github.piresrenan.orderhub.authorization.domain.model;

import java.util.UUID;

/**
 * First concrete authorization scope supported by OrderHub.
 *
 * <p>
 * OH-013 deliberately implements Tenant scope only. Platform,
 * Organization/Network and Resource scopes remain future extensions.
 * </p>
 */
public record TenantAuthorizationScope(
        UUID tenantId) {

    public TenantAuthorizationScope {

        if (tenantId == null) {
            throw new IllegalArgumentException(
                    "Authorization tenant id is required");
        }
    }
}
