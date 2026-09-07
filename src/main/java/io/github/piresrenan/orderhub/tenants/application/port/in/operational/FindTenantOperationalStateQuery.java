package io.github.piresrenan.orderhub.tenants.application.port.in.operational;

import java.util.UUID;

public record FindTenantOperationalStateQuery(
        UUID tenantId) {

    public FindTenantOperationalStateQuery {

        if (tenantId == null) {
            throw new IllegalArgumentException(
                    "Tenant id is required");
        }
    }
}
