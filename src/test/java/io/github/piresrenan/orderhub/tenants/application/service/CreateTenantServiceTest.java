package io.github.piresrenan.orderhub.tenants.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import io.github.piresrenan.orderhub.tenants.application.port.in.CreateTenantCommand;
import io.github.piresrenan.orderhub.tenants.application.port.out.TenantIdGenerator;
import io.github.piresrenan.orderhub.tenants.application.port.out.TenantRepository;
import io.github.piresrenan.orderhub.tenants.domain.model.Tenant;

class CreateTenantServiceTest {

    @Test
    void createsAndPersistsTenant() {
        // Why: Tenant creation must coordinate identity generation, domain creation
        // and persistence through application-owned output ports.
        // Covers: complete happy-path orchestration of CreateTenantService.
        // Prevents: Tenant state bypassing the domain or being returned without
        // crossing the persistence boundary.

        var tenantId = UUID.randomUUID();
        var repository = new RecordingTenantRepository();

        TenantIdGenerator idGenerator = () -> tenantId;

        var service = new CreateTenantService(
                repository,
                idGenerator);

        var tenant = service.create(
                new CreateTenantCommand(
                        "  Acme Commerce  "));

        assertThat(tenant.id())
                .isEqualTo(tenantId);

        assertThat(tenant.name())
                .isEqualTo("Acme Commerce");

        assertThat(repository.savedTenant)
                .isSameAs(tenant);

        assertThat(repository.saveCount)
                .isEqualTo(1);
    }

    @Test
    void generatesTenantIdExactlyOnce() {
        // Why: aggregate identity generation must have deterministic cardinality
        // even if its future implementation changes.
        // Covers: interaction with TenantIdGenerator during one creation request.
        // Prevents: multiple identifiers being generated for one logical Tenant.

        var calls = new AtomicInteger();
        var generatedId = UUID.randomUUID();

        TenantIdGenerator idGenerator = () -> {
            calls.incrementAndGet();
            return generatedId;
        };

        var service = new CreateTenantService(
                new RecordingTenantRepository(),
                idGenerator);

        var tenant = service.create(
                new CreateTenantCommand(
                        "Acme Commerce"));

        assertThat(calls)
                .hasValue(1);

        assertThat(tenant.id())
                .isEqualTo(generatedId);
    }

    @Test
    void doesNotPersistInvalidTenant() {
        // Why: persistence must only receive aggregates that successfully satisfy
        // domain invariants.
        // Covers: ordering between aggregate creation and repository invocation.
        // Prevents: invalid Tenant state crossing the persistence boundary.

        var repository = new RecordingTenantRepository();

        var service = new CreateTenantService(
                repository,
                UUID::randomUUID);

        var invalidCommand = new CreateTenantCommand(
                "   ");

        assertThatThrownBy(() -> service.create(invalidCommand))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Tenant name must not be blank");

        assertThat(repository.saveCount)
                .isZero();

        assertThat(repository.savedTenant)
                .isNull();
    }

    private static final class RecordingTenantRepository
            implements TenantRepository {

        private Tenant savedTenant;
        private int saveCount;

        /**
         * Records the aggregate sent through the persistence output port.
         *
         * @param tenant aggregate requested for persistence
         * @return the same aggregate supplied by the application service
         */
        @Override
        public Tenant save(Tenant tenant) {
            this.savedTenant = tenant;
            this.saveCount++;

            return tenant;
        }

        /**
         * Satisfies the Tenant lookup contract without introducing read behavior
         * unrelated to CreateTenantService.
         *
         * @param tenantId Tenant aggregate identifier
         * @return always empty because reads are outside this test double's purpose
         */
        @Override
        public Optional<Tenant> findById(UUID tenantId) {
            return Optional.empty();
        }
    }
}
