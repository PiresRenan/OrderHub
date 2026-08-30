package io.github.piresrenan.orderhub.tenants.application.port.out;

import java.util.UUID;

@FunctionalInterface
public interface TenantIdGenerator {

    /**
     * Generates the identity for one new Tenant aggregate.
     *
     * @return non-null Tenant identifier
     */
    UUID generate();
}
