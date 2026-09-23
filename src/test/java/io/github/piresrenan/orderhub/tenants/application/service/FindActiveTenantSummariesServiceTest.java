package io.github.piresrenan.orderhub.tenants.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import io.github.piresrenan.orderhub.tenants.application.port.in.operational.ActiveTenantSummary;
import io.github.piresrenan.orderhub.tenants.application.port.in.operational.FindActiveTenantSummariesQuery;
import io.github.piresrenan.orderhub.tenants.application.port.in.operational.TenantOperationalStateUnavailableException;
import io.github.piresrenan.orderhub.tenants.application.port.out.TenantPersistenceException;
import io.github.piresrenan.orderhub.tenants.domain.model.Tenant;

/**
 * Why: Tenant operational policy must stay inside Tenants while discovery stays one bounded read.
 * Scenario: batches mixing ACTIVE and SUSPENDED Tenants, an empty batch and persistence failure.
 * Covers: ACTIVE-only filtering, empty-batch short circuit, batch bound and failure classification.
 * Expected: only ACTIVE summaries; no read for an empty batch; persistence failure is classified.
 * Prevents: suspended Tenants becoming selectable and oversized batch reads.
 */
class FindActiveTenantSummariesServiceTest {

    @Test void returnsOnlyActiveTenantsFromOneRead() {
        var active = Tenant.create(UUID.randomUUID(), "Active Commerce");
        var suspended = Tenant.create(UUID.randomUUID(), "Suspended Commerce").suspend();
        var reads = new AtomicInteger();
        var service = new FindActiveTenantSummariesService(ids -> { reads.incrementAndGet(); return List.of(active, suspended); });
        assertThat(service.find(new FindActiveTenantSummariesQuery(List.of(active.id(), suspended.id(), UUID.randomUUID()))))
                .containsExactly(new ActiveTenantSummary(active.id(), "Active Commerce"));
        assertThat(reads.get()).isEqualTo(1);
    }

    @Test void emptyBatchPerformsNoRead() {
        var service = new FindActiveTenantSummariesService(ids -> { throw new AssertionError("no read expected"); });
        assertThat(service.find(new FindActiveTenantSummariesQuery(List.of()))).isEmpty();
    }

    @Test void persistenceFailureIsClassifiedAsUnavailable() {
        var service = new FindActiveTenantSummariesService(ids -> { throw new TenantPersistenceException(new IllegalStateException("db")); });
        assertThatThrownBy(() -> service.find(new FindActiveTenantSummariesQuery(List.of(UUID.randomUUID()))))
                .isInstanceOf(TenantOperationalStateUnavailableException.class);
    }

    @Test void batchIsBounded() {
        assertThatThrownBy(() -> new FindActiveTenantSummariesQuery(Collections.nCopies(101, UUID.randomUUID())))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
