package io.github.piresrenan.orderhub.users.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.github.piresrenan.orderhub.users.application.port.in.ScanOperationallyActiveMembershipTenantsQuery;
import io.github.piresrenan.orderhub.users.application.port.in.TenantMembershipScanUnavailableException;
import io.github.piresrenan.orderhub.users.application.port.out.TenantMembershipPersistenceException;

/**
 * Why: the end of discovery must be proven by Users, not guessed by clients from short pages.
 * Scenario: the scan repository returns exactly window, window + 1 or fewer rows.
 * Covers: the single windowSize + 1 read, hasMore derivation, window trimming and failure classification.
 * Expected: one read per scan; hasMore only when an extra row exists; persistence failure is classified.
 * Prevents: unbounded reads, off-by-one continuation and leaked persistence exception types.
 */
class ScanOperationallyActiveMembershipTenantsServiceTest {
    private final UUID user = UUID.randomUUID();

    @Test void readsOneExtraRowToProveMoreRemainAndTrimsIt() {
        var rows = ids(4); var requested = new ArrayList<Integer>();
        var service = new ScanOperationallyActiveMembershipTenantsService((userId, after, max) -> { requested.add(max); return rows; });
        var scan = service.scan(new ScanOperationallyActiveMembershipTenantsQuery(user, null, 3));
        assertThat(requested).containsExactly(4);
        assertThat(scan.tenantIds()).containsExactlyElementsOf(rows.subList(0, 3));
        assertThat(scan.hasMore()).isTrue();
    }

    @Test void exactOrShortWindowIsFinal() {
        var rows = ids(3);
        var service = new ScanOperationallyActiveMembershipTenantsService((userId, after, max) -> rows);
        var scan = service.scan(new ScanOperationallyActiveMembershipTenantsQuery(user, UUID.randomUUID(), 3));
        assertThat(scan.tenantIds()).containsExactlyElementsOf(rows);
        assertThat(scan.hasMore()).isFalse();
    }

    @Test void persistenceFailureIsClassifiedAsUnavailable() {
        var service = new ScanOperationallyActiveMembershipTenantsService((userId, after, max) -> {
            throw new TenantMembershipPersistenceException(new IllegalStateException("db"));
        });
        assertThatThrownBy(() -> service.scan(new ScanOperationallyActiveMembershipTenantsQuery(user, null, 1)))
                .isInstanceOf(TenantMembershipScanUnavailableException.class);
    }

    @Test void queryRejectsUnboundedWindows() {
        assertThatThrownBy(() -> new ScanOperationallyActiveMembershipTenantsQuery(user, null, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ScanOperationallyActiveMembershipTenantsQuery(user, null, 101)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ScanOperationallyActiveMembershipTenantsQuery(null, null, 1)).isInstanceOf(IllegalArgumentException.class);
    }

    private static List<UUID> ids(int count) {
        var ids = new ArrayList<UUID>();
        for (int i = 0; i < count; i++) { ids.add(UUID.randomUUID()); }
        return ids;
    }
}
