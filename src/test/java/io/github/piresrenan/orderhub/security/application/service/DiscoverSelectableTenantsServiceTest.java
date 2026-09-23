package io.github.piresrenan.orderhub.security.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import io.github.piresrenan.orderhub.security.application.model.AuthenticatedUserPrincipal;
import io.github.piresrenan.orderhub.security.application.port.in.DiscoverSelectableTenantsQuery;
import io.github.piresrenan.orderhub.security.application.port.in.SelectableTenantPage.SelectableTenant;
import io.github.piresrenan.orderhub.tenants.application.port.in.operational.ActiveTenantSummary;
import io.github.piresrenan.orderhub.tenants.application.port.in.operational.FindActiveTenantSummariesUseCase;
import io.github.piresrenan.orderhub.tenants.application.port.in.operational.TenantOperationalStateUnavailableException;
import io.github.piresrenan.orderhub.users.application.port.in.OperationallyActiveMembershipTenantScan;
import io.github.piresrenan.orderhub.users.application.port.in.ScanOperationallyActiveMembershipTenantsQuery;
import io.github.piresrenan.orderhub.users.application.port.in.ScanOperationallyActiveMembershipTenantsUseCase;
import io.github.piresrenan.orderhub.users.application.port.in.TenantMembershipScanUnavailableException;

/**
 * Why: ADR-0021 bounded scan pagination is the only guard against filtering-dependent cost and cursor loops.
 * Scenario: owner answers are controlled so every window shape can be asserted exactly.
 * Covers: one Users scan and at most one Tenants batch per request, continuation from the last scanned
 * candidate, identifier ordering, and technical-failure propagation.
 * Expected: bounded owner calls; empty or short pages keep a continuation; only real failures escape.
 * Prevents: N+1 or refill loops, rescanning filtered Tenants, cursor derived from returned items,
 * and filtering being reported as a failure.
 */
class DiscoverSelectableTenantsServiceTest {
    private static final AuthenticatedUserPrincipal ACTOR = new AuthenticatedUserPrincipal(UUID.randomUUID());
    private final UUID a = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private final UUID b = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private final UUID c = UUID.fromString("00000000-0000-0000-0000-00000000000c");

    @Test void allFilteredWindowReturnsEmptyItemsWithLastScannedCandidateAsContinuation() {
        var fixture = new Fixture(new OperationallyActiveMembershipTenantScan(List.of(a, b, c), true), Set.of());
        var page = fixture.service().discover(new DiscoverSelectableTenantsQuery(ACTOR, null, 3));
        assertThat(page.items()).isEmpty();
        assertThat(page.nextAfterId()).isEqualTo(c);
        fixture.assertBoundedCalls();
    }

    @Test void shortPageKeepsScanOrderAndContinuesFromScanNotFromReturnedTenant() {
        var fixture = new Fixture(new OperationallyActiveMembershipTenantScan(List.of(a, b, c), true), Set.of(a, b));
        var page = fixture.service().discover(new DiscoverSelectableTenantsQuery(ACTOR, null, 3));
        assertThat(page.items()).extracting(SelectableTenant::id).containsExactly(a, b);
        assertThat(page.nextAfterId()).isEqualTo(c);
        fixture.assertBoundedCalls();
    }

    @Test void finalWindowHasNoContinuationAndForwardsCallerCursorAndPrincipalOnly() {
        var fixture = new Fixture(new OperationallyActiveMembershipTenantScan(List.of(b, c), false), Set.of(c));
        var page = fixture.service().discover(new DiscoverSelectableTenantsQuery(ACTOR, a, 50));
        assertThat(page.items()).containsExactly(new SelectableTenant(c, "Tenant " + c));
        assertThat(page.nextAfterId()).isNull();
        assertThat(fixture.scans).containsExactly(new ScanOperationallyActiveMembershipTenantsQuery(ACTOR.userId(), a, 50));
        fixture.assertBoundedCalls();
    }

    @Test void emptyScanWindowIsFinalAndPerformsNoTenantsRead() {
        var service = new DiscoverSelectableTenantsService(
                query -> new OperationallyActiveMembershipTenantScan(List.of(), false),
                query -> { throw new AssertionError("no Tenants read expected for an empty window"); });
        var page = service.discover(new DiscoverSelectableTenantsQuery(ACTOR, a, 10));
        assertThat(page.items()).isEmpty();
        assertThat(page.nextAfterId()).isNull();
    }

    @Test void technicalFailuresEscapeUnchangedAndAreNeverTranslatedIntoEmptyPages() {
        ScanOperationallyActiveMembershipTenantsUseCase failingScan = query -> { throw new TenantMembershipScanUnavailableException(new IllegalStateException()); };
        var service = new DiscoverSelectableTenantsService(failingScan, query -> List.of());
        assertThatThrownBy(() -> service.discover(new DiscoverSelectableTenantsQuery(ACTOR, null, 5)))
                .isInstanceOf(TenantMembershipScanUnavailableException.class);

        FindActiveTenantSummariesUseCase failingTenants = query -> { throw new TenantOperationalStateUnavailableException(new IllegalStateException()); };
        var second = new DiscoverSelectableTenantsService(query -> new OperationallyActiveMembershipTenantScan(List.of(a), false), failingTenants);
        assertThatThrownBy(() -> second.discover(new DiscoverSelectableTenantsQuery(ACTOR, null, 5)))
                .isInstanceOf(TenantOperationalStateUnavailableException.class);
    }

    @Test void queryRejectsUnboundedOrAnonymousRequests() {
        assertThatThrownBy(() -> new DiscoverSelectableTenantsQuery(ACTOR, null, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DiscoverSelectableTenantsQuery(ACTOR, null, 101)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DiscoverSelectableTenantsQuery(null, null, 10)).isInstanceOf(IllegalArgumentException.class);
    }

    private static final class Fixture {
        private final OperationallyActiveMembershipTenantScan scan;
        private final Set<UUID> active;
        private final List<ScanOperationallyActiveMembershipTenantsQuery> scans = new ArrayList<>();
        private final AtomicInteger tenantReads = new AtomicInteger();

        Fixture(OperationallyActiveMembershipTenantScan scan, Set<UUID> active) { this.scan = scan; this.active = active; }

        DiscoverSelectableTenantsService service() {
            return new DiscoverSelectableTenantsService(
                    query -> { scans.add(query); return scan; },
                    query -> {
                        tenantReads.incrementAndGet();
                        assertThat(query.tenantIds()).isEqualTo(scan.tenantIds());
                        // Reverse order proves the page order comes from the scan, not the batch read.
                        return query.tenantIds().reversed().stream().filter(active::contains)
                                .map(id -> new ActiveTenantSummary(id, "Tenant " + id)).toList();
                    });
        }

        void assertBoundedCalls() {
            assertThat(scans).hasSize(1);
            assertThat(tenantReads.get()).isEqualTo(1);
        }
    }
}
