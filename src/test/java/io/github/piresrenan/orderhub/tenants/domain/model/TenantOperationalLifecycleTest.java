package io.github.piresrenan.orderhub.tenants.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.Test;

class TenantOperationalLifecycleTest {

    @Test
    void createsTenantAsActive() {

        var tenant = Tenant.create(
                UUID.randomUUID(),
                "Acme Commerce");

        assertThat(tenant.status())
                .isEqualTo(TenantStatus.ACTIVE);
    }

    @Test
    void rehydratesPersistedSuspendedTenant() {

        var tenant = Tenant.rehydrate(
                UUID.randomUUID(),
                "Acme Commerce",
                TenantStatus.SUSPENDED);

        assertThat(tenant.status())
                .isEqualTo(TenantStatus.SUSPENDED);
    }

    @Test
    void rejectsPersistedTenantWithoutOperationalStatus() {

        assertThatThrownBy(() ->
                Tenant.rehydrate(
                        UUID.randomUUID(),
                        "Acme Commerce",
                        null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Tenant status is required");
    }

    @Test
    void transitionsTenantWithoutMutatingPriorState() {

        var active = Tenant.create(
                UUID.randomUUID(),
                "Acme Commerce");

        var suspended =
                active.suspend();

        assertThat(active.status())
                .isEqualTo(TenantStatus.ACTIVE);

        assertThat(suspended.id())
                .isEqualTo(active.id());

        assertThat(suspended.name())
                .isEqualTo(active.name());

        assertThat(suspended.status())
                .isEqualTo(TenantStatus.SUSPENDED);

        var recovered =
                suspended.recover();

        assertThat(suspended.status())
                .isEqualTo(TenantStatus.SUSPENDED);

        assertThat(recovered.id())
                .isEqualTo(active.id());

        assertThat(recovered.name())
                .isEqualTo(active.name());

        assertThat(recovered.status())
                .isEqualTo(TenantStatus.ACTIVE);
    }
}
