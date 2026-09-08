package io.github.piresrenan.orderhub.workforce;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

import io.github.piresrenan.orderhub.OrderHubApplication;

class StaffTenantAuthorizationContractTest {

    /**
     * Why: business administration needs current Staff authority without foreign persistence.
     * Covers: an explicit workforce-owned public application boundary.
     * Prevents: making callers reconstruct organizational authority or bypass the ceiling.
     */
    @Test
    void exposesCurrentStaffAuthorizationAsAnExplicitNamedInterface() {
        var workforce = ApplicationModules.of(OrderHubApplication.class)
                .getModuleByName("workforce").orElseThrow();
        assertThat(workforce.getNamedInterfaces().getByName("staff-authorization"))
                .isPresent();
    }
}
