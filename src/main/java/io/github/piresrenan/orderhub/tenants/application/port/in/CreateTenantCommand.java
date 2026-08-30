package io.github.piresrenan.orderhub.tenants.application.port.in;

/**
 * Carries the application input required to create a Tenant.
 *
 * <p>
 * Transport-specific validation and representation belong to inbound adapters.
 * Domain invariants remain enforced by the Tenant aggregate.
 * </p>
 *
 * @param name human-readable Tenant name supplied to the creation use case
 */
public record CreateTenantCommand(
        String name) {
}
