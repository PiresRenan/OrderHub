package io.github.piresrenan.orderhub.customers.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.Test;

class CustomerProfileTest {

    @Test
    void keepsTenantScopedCommercialIdentity() {

        var tenantId =
                UUID.randomUUID();

        var customerId =
                UUID.randomUUID();

        var profile =
                new CustomerProfile(
                        tenantId,
                        customerId);

        assertThat(profile.tenantId())
                .isEqualTo(
                        tenantId);

        assertThat(profile.customerId())
                .isEqualTo(
                        customerId);

        assertThat(profile.tenantId())
                .isNotEqualTo(
                        profile.customerId());
    }

    @Test
    void rejectsMissingTenantId() {

        assertThatThrownBy(() ->
                new CustomerProfile(
                        null,
                        UUID.randomUUID()))
                .isInstanceOf(
                        IllegalArgumentException.class)
                .hasMessage(
                        "Tenant ID is required");
    }

    @Test
    void rejectsMissingCustomerId() {

        assertThatThrownBy(() ->
                new CustomerProfile(
                        UUID.randomUUID(),
                        null))
                .isInstanceOf(
                        IllegalArgumentException.class)
                .hasMessage(
                        "Customer ID is required");
    }
}
