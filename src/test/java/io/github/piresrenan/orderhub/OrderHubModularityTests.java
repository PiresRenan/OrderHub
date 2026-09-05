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
        void detectsOrdersTenantsAndUsersAsApplicationModules() {
                // Why: package organization alone does not prove that Spring Modulith
                // recognizes each business capability as an independent application module.
                // Covers: explicit discovery and distinct identity of Orders, Tenants and
                // Users module roots.
                // Prevents: structural refactoring silently collapsing or excluding an
                // intended module boundary.

                var modules = ApplicationModules.of(
                                OrderHubApplication.class);

                var ordersModule = modules.getModuleByName(
                                "orders");

                var tenantsModule = modules.getModuleByName(
                                "tenants");

                var usersModule = modules.getModuleByName(
                                "users");

                assertThat(ordersModule)
                                .as("Orders must be detected as an application module")
                                .isPresent();

                assertThat(tenantsModule)
                                .as("Tenants must be detected as an application module")
                                .isPresent();

                assertThat(usersModule)
                                .as("Users must be detected as an application module")
                                .isPresent();

                var orders = ordersModule.orElseThrow();
                var tenants = tenantsModule.orElseThrow();
                var users = usersModule.orElseThrow();

                assertThat(orders)
                                .isNotSameAs(tenants)
                                .isNotSameAs(users);

                assertThat(tenants)
                                .isNotSameAs(users);
        }
        /**
         * Verifies that Organizations remains a distinct top-level application
         * module as its domain grows beyond the initial aggregate bootstrap.
         */
        @Test
        void detectsOrganizationsAsApplicationModule() {
                // Why: Organizations already lives at a Spring Modulith module
                // root by package structure; this locks that architectural fact
                // explicitly rather than relying on incidental discovery.
                // Covers: Organizations module detection and module identity.
                // Prevents: future package refactoring silently absorbing or
                // excluding Organizations from the modular model.

                var modules = ApplicationModules.of(
                                OrderHubApplication.class);

                var organizationsModule = modules.getModuleByName(
                                "organizations");

                assertThat(organizationsModule)
                                .as(
                                                "Organizations must be detected"
                                                                + " as an application module")
                                .isPresent();
        }
}
