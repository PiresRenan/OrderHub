package io.github.piresrenan.orderhub.workforce;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

import io.github.piresrenan.orderhub.OrderHubApplication;

class WorkforceFoundationContractTest {

    @Test
    void workforceIsDetectedAsAnIndependentApplicationModule() {

        var modules =
                ApplicationModules.of(
                        OrderHubApplication.class);

        assertThat(
                modules.getModuleByName(
                        "workforce"))
                .isPresent();
    }

    @Test
    void staffProfileContractExists() {

        assertThatCode(() ->
                Class.forName(
                        "io.github.piresrenan.orderhub.workforce.domain.model.StaffProfile"))
                .doesNotThrowAnyException();
    }

    @Test
    void departmentContractExists() {

        assertThatCode(() ->
                Class.forName(
                        "io.github.piresrenan.orderhub.workforce.domain.model.Department"))
                .doesNotThrowAnyException();
    }

    @Test
    void jobPositionContractExists() {

        assertThatCode(() ->
                Class.forName(
                        "io.github.piresrenan.orderhub.workforce.domain.model.JobPosition"))
                .doesNotThrowAnyException();
    }

    @Test
    void reportingStructurePolicyContractExists() {

        assertThatCode(() ->
                Class.forName(
                        "io.github.piresrenan.orderhub.workforce.domain.policy.ReportingStructurePolicy"))
                .doesNotThrowAnyException();
    }
}
