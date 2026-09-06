package io.github.piresrenan.orderhub.tenants.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import io.github.piresrenan.orderhub.tenants.application.port.in.operational.FindTenantOperationalStateQuery;
import io.github.piresrenan.orderhub.tenants.application.port.in.operational.TenantOperationalState;
import io.github.piresrenan.orderhub.tenants.application.port.in.operational.TenantOperationalStateUnavailableException;
import io.github.piresrenan.orderhub.tenants.application.port.out.TenantPersistenceException;
import io.github.piresrenan.orderhub.tenants.application.port.out.TenantRepository;
import io.github.piresrenan.orderhub.tenants.domain.model.Tenant;

class FindTenantOperationalStateServiceTest {

    @Test
    void exposesActiveTenantAsActiveOperationalState() {

        var tenant =
                Tenant.create(
                        UUID.randomUUID(),
                        "Acme Commerce");

        var service =
                new FindTenantOperationalStateService(
                        repositoryFinding(
                                ignored ->
                                        Optional.of(
                                                tenant)));

        var result =
                service.find(
                        new FindTenantOperationalStateQuery(
                                tenant.id()));

        assertThat(result)
                .contains(
                        TenantOperationalState.ACTIVE);
    }

    @Test
    void exposesSuspendedTenantAsSuspendedOperationalState() {

        var tenant =
                Tenant.create(
                                UUID.randomUUID(),
                                "Acme Commerce")
                        .suspend();

        var service =
                new FindTenantOperationalStateService(
                        repositoryFinding(
                                ignored ->
                                        Optional.of(
                                                tenant)));

        var result =
                service.find(
                        new FindTenantOperationalStateQuery(
                                tenant.id()));

        assertThat(result)
                .contains(
                        TenantOperationalState.SUSPENDED);
    }

    @Test
    void returnsEmptyWhenTenantDoesNotExist() {

        var service =
                new FindTenantOperationalStateService(
                        repositoryFinding(
                                ignored ->
                                        Optional.empty()));

        var result =
                service.find(
                        new FindTenantOperationalStateQuery(
                                UUID.randomUUID()));

        assertThat(result)
                .isEmpty();
    }

    @Test
    void translatesPersistenceFailureIntoPublicOperationalStateFailure() {

        var internalDetail =
                "synthetic-private-database-detail";

        var persistenceFailure =
                new TenantPersistenceException(
                        new IllegalStateException(
                                internalDetail));

        var service =
                new FindTenantOperationalStateService(
                        repositoryFinding(
                                ignored -> {
                                    throw persistenceFailure;
                                }));

        assertThatThrownBy(() ->
                service.find(
                        new FindTenantOperationalStateQuery(
                                UUID.randomUUID())))
                .isInstanceOf(
                        TenantOperationalStateUnavailableException.class)
                .hasMessage(
                        "Tenant operational state is unavailable.")
                .hasCause(
                        persistenceFailure)
                .satisfies(exception ->
                        assertThat(
                                exception.getMessage())
                                .doesNotContain(
                                        internalDetail,
                                        "SQL",
                                        "JDBC",
                                        "PostgreSQL"));
    }

    @Test
    void rejectsMissingTenantRepository() {

        assertThatThrownBy(() ->
                new FindTenantOperationalStateService(
                        null))
                .isInstanceOf(
                        IllegalArgumentException.class)
                .hasMessage(
                        "Tenant repository is required");
    }

    @Test
    void rejectsMissingOperationalStateQuery() {

        var service =
                new FindTenantOperationalStateService(
                        repositoryFinding(
                                ignored ->
                                        Optional.empty()));

        assertThatThrownBy(() ->
                service.find(
                        null))
                .isInstanceOf(
                        IllegalArgumentException.class)
                .hasMessage(
                        "Tenant operational-state query is required");
    }

    @Test
    void rejectsMissingTenantIdentityAtPublicQueryBoundary() {

        assertThatThrownBy(() ->
                new FindTenantOperationalStateQuery(
                        null))
                .isInstanceOf(
                        IllegalArgumentException.class)
                .hasMessage(
                        "Tenant id is required");
    }

    private static TenantRepository repositoryFinding(
            Function<UUID, Optional<Tenant>> finder) {

        return new TenantRepository() {

            @Override
            public Tenant save(
                    Tenant tenant) {

                throw new UnsupportedOperationException(
                        "Save is outside this test boundary");
            }

            @Override
            public Optional<Tenant> findById(
                    UUID tenantId) {

                return finder.apply(
                        tenantId);
            }
        };
    }
}
