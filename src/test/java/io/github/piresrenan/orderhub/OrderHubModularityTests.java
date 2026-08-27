package io.github.piresrenan.orderhub;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

class OrderHubModularityTests {

    @Test
    void verifiesApplicationModuleBoundaries() {
        // Why: architectural violations often compile and survive normal unit tests.
        // Covers: module cycles and illegal access to module internals.
        // Prevents: gradual erosion of the intended modular/hexagonal architecture.

        ApplicationModules
                .of(OrderHubApplication.class)
                .verify();
    }
}