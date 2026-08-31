package io.github.piresrenan.orderhub.users.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.Test;

class TenantMembershipTest {

    @Test
    void createsMembershipBetweenUserAndTenant() {
        // Why: membership must explicitly associate one internal User identity with
        // one Tenant identity without introducing authorization semantics.
        // Covers: valid TenantMembership creation.
        // Prevents: hidden role, credential or Tenant-object coupling entering the
        // membership model.

        var userId = UUID.randomUUID();
        var tenantId = UUID.randomUUID();

        var membership = TenantMembership.create(
                userId,
                tenantId);

        assertThat(membership.userId())
                .isEqualTo(userId);

        assertThat(membership.tenantId())
                .isEqualTo(tenantId);
    }

    @Test
    void rejectsMissingUserId() {
        // Why: a membership without a User cannot represent a valid association.
        // Covers: required userId invariant.
        // Prevents: orphan membership state on the User side.

        assertThatThrownBy(() ->
                TenantMembership.create(
                        null,
                        UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Membership user id is required");
    }

    @Test
    void rejectsMissingTenantId() {
        // Why: a membership without a Tenant cannot represent a valid association.
        // Covers: required tenantId invariant.
        // Prevents: orphan membership state on the Tenant side.

        assertThatThrownBy(() ->
                TenantMembership.create(
                        UUID.randomUUID(),
                        null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Membership tenant id is required");
    }

    @Test
    void rehydratesPersistedMembership() {
        // Why: repository adapters need an explicit reconstruction contract for
        // already persisted associations.
        // Covers: TenantMembership rehydration.
        // Prevents: persistence bypassing domain invariants during reads.

        var userId = UUID.randomUUID();
        var tenantId = UUID.randomUUID();

        var membership = TenantMembership.rehydrate(
                userId,
                tenantId);

        assertThat(membership.userId())
                .isEqualTo(userId);

        assertThat(membership.tenantId())
                .isEqualTo(tenantId);
    }

    @Test
    void rehydrationRejectsMissingUserId() {
        // Why: persisted membership state must satisfy the same identity
        // requirements as newly created state.
        // Covers: userId validation during reconstruction.
        // Prevents: corrupted persistence state entering the domain.

        assertThatThrownBy(() ->
                TenantMembership.rehydrate(
                        null,
                        UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Membership user id is required");
    }

    @Test
    void rehydrationRejectsMissingTenantId() {
        // Why: reconstruction must reject associations without Tenant identity.
        // Covers: tenantId validation during reconstruction.
        // Prevents: corrupted membership rows becoming valid domain objects.

        assertThatThrownBy(() ->
                TenantMembership.rehydrate(
                        UUID.randomUUID(),
                        null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Membership tenant id is required");
    }
}
