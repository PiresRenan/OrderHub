package io.github.piresrenan.orderhub.users.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.Test;

class TenantMembershipTest {

    @Test
    void createsActiveMembershipBetweenUserAndTenant() {
        // Why: a newly established relationship must be immediately usable, and
        // that starting lifecycle must be a deliberate domain decision rather
        // than a persistence default.
        // Covers: creation producing ACTIVE identity plus lifecycle state.
        // Prevents: new memberships depending on a database default, or being
        // created in a state that cannot participate in Tenant trust.


        var userId = UUID.randomUUID();
        var tenantId = UUID.randomUUID();

        var membership = TenantMembership.create(
                userId,
                tenantId);

        assertThat(membership.userId())
                .isEqualTo(userId);

        assertThat(membership.tenantId())
                .isEqualTo(tenantId);

        assertThat(membership.status())
                .isEqualTo(TenantMembershipStatus.ACTIVE);

        assertThat(membership.isOperationallyActive())
                .isTrue();
    }

    @Test
    void rejectsMissingUserId() {
        // Why: a membership without a User cannot represent a valid association.
        // Covers: required userId invariant on creation.
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
        // Covers: required tenantId invariant on creation.
        // Prevents: orphan membership state on the Tenant side.


        assertThatThrownBy(() ->
                TenantMembership.create(
                        UUID.randomUUID(),
                        null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Membership tenant id is required");
    }

    @Test
    void strictlyRehydratesPersistedMembershipStatus() {
        // Why: a relationship may be preserved for history while no longer being
        // eligible to establish new Tenant trust, so reconstruction must report
        // the persisted lifecycle exactly.
        // Covers: SUSPENDED and TERMINATED rehydration and their non-operational
        // answer.
        // Prevents: a suspended or terminated relationship silently regaining
        // operational authority through lenient reconstruction.


        var userId = UUID.randomUUID();
        var tenantId = UUID.randomUUID();

        var suspended = TenantMembership.rehydrate(
                userId,
                tenantId,
                TenantMembershipStatus.SUSPENDED);

        var terminated = TenantMembership.rehydrate(
                userId,
                tenantId,
                TenantMembershipStatus.TERMINATED);

        assertThat(suspended.status())
                .isEqualTo(TenantMembershipStatus.SUSPENDED);

        assertThat(suspended.isOperationallyActive())
                .isFalse();

        assertThat(terminated.status())
                .isEqualTo(TenantMembershipStatus.TERMINATED);

        assertThat(terminated.isOperationallyActive())
                .isFalse();
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
                        UUID.randomUUID(),
                        TenantMembershipStatus.ACTIVE))
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
                        null,
                        TenantMembershipStatus.ACTIVE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Membership tenant id is required");
    }

    @Test
    void rehydrationRejectsMissingStatus() {
        // Why: lifecycle is what decides operational eligibility, so absent
        // status is corrupt state rather than a defaultable value.
        // Covers: mandatory status during reconstruction.
        // Prevents: a missing persisted lifecycle being interpreted as ACTIVE.


        assertThatThrownBy(() ->
                TenantMembership.rehydrate(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Membership status is required");
    }
}
