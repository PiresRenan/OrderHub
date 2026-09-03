package io.github.piresrenan.orderhub.customers.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.Test;

class CustomerAccountBindingTest {

    @Test
    void keepsExactTenantCustomerAndUserRelationship() {

        var tenantId =
                UUID.randomUUID();

        var customerId =
                UUID.randomUUID();

        var userId =
                UUID.randomUUID();

        var binding =
                new CustomerAccountBinding(
                        tenantId,
                        customerId,
                        userId);

        assertThat(binding.tenantId())
                .isEqualTo(
                        tenantId);

        assertThat(binding.customerId())
                .isEqualTo(
                        customerId);

        assertThat(binding.userId())
                .isEqualTo(
                        userId);
    }

    @Test
    void rejectsMissingTenantId() {

        assertThatThrownBy(() ->
                new CustomerAccountBinding(
                        null,
                        UUID.randomUUID(),
                        UUID.randomUUID()))
                .isInstanceOf(
                        IllegalArgumentException.class)
                .hasMessage(
                        "Tenant ID is required");
    }

    @Test
    void rejectsMissingCustomerId() {

        assertThatThrownBy(() ->
                new CustomerAccountBinding(
                        UUID.randomUUID(),
                        null,
                        UUID.randomUUID()))
                .isInstanceOf(
                        IllegalArgumentException.class)
                .hasMessage(
                        "Customer ID is required");
    }

    @Test
    void rejectsMissingUserId() {

        assertThatThrownBy(() ->
                new CustomerAccountBinding(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        null))
                .isInstanceOf(
                        IllegalArgumentException.class)
                .hasMessage(
                        "User ID is required");
    }
}
