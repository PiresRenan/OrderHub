package io.github.piresrenan.orderhub.workforce.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.Test;

class DepartmentTest {

    @Test
    void departmentIsConfigurableAndTenantScoped() {

        var tenantId =
                UUID.randomUUID();

        var department =
                new Department(
                        UUID.randomUUID(),
                        tenantId,
                        " OPS ",
                        " Operations ");

        assertThat(department.tenantId())
                .isEqualTo(tenantId);

        assertThat(department.code())
                .isEqualTo("OPS");

        assertThat(department.name())
                .isEqualTo("Operations");
    }

    @Test
    void rejectsIncompleteDepartment() {

        var id =
                UUID.randomUUID();

        assertThatThrownBy(() ->
                new Department(
                        null,
                        id,
                        "OPS",
                        "Operations"))
                .isInstanceOf(
                        IllegalArgumentException.class);

        assertThatThrownBy(() ->
                new Department(
                        id,
                        null,
                        "OPS",
                        "Operations"))
                .isInstanceOf(
                        IllegalArgumentException.class);

        assertThatThrownBy(() ->
                new Department(
                        id,
                        id,
                        " ",
                        "Operations"))
                .isInstanceOf(
                        IllegalArgumentException.class);

        assertThatThrownBy(() ->
                new Department(
                        id,
                        id,
                        "OPS",
                        " "))
                .isInstanceOf(
                        IllegalArgumentException.class);
    }
}
