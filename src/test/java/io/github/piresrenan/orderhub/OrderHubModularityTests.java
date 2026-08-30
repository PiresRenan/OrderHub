package io.github.piresrenan.orderhub;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

class OrderHubModularityTests {

    /**
     * Verifies the structural rules enforced across every detected application
     * module.
     */
    @Test
    void verifiesApplicationModuleBoundaries() {
        // Why: architectural violations often compile and survive normal unit tests.
        // Covers: module cycles and illegal access to module internals.
        // Prevents: gradual erosion of the intended modular/hexagonal architecture.

        ApplicationModules
                .of(OrderHubApplication.class)
                .verify();
    }

    /**
     * Verifies that the expected top-level business capabilities are recognized
     * as independent Spring Modulith application modules.
     */
    @Test
    void detectsOrdersAndTenantsAsApplicationModules() {
        // Why: package organization alone does not prove that Spring Modulith
        // recognizes a capability as an application module.
        // Covers: explicit discovery of the Orders and Tenants module roots.
        // Prevents: structural refactoring silently collapsing or excluding one of
        // the intended module boundaries.

        var modules = ApplicationModules.of(
                OrderHubApplication.class);

        var ordersModule = modules.getModuleByName(
                "orders");

        var tenantsModule = modules.getModuleByName(
                "tenants");

        assertThat(ordersModule)
                .as("Orders must be detected as an application module")
                .isPresent();

        assertThat(tenantsModule)
                .as("Tenants must be detected as an application module")
                .isPresent();

        assertThat(ordersModule.orElseThrow())
                .isNotSameAs(tenantsModule.orElseThrow());
    }
}
