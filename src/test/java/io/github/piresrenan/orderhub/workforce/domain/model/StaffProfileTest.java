package io.github.piresrenan.orderhub.workforce.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.Test;

class StaffProfileTest {

    @Test
    void activeProfileUsesOpaqueTenantAndUserIdentity() {

        var staffId =
                UUID.randomUUID();

        var userId =
                UUID.randomUUID();

        var tenantId =
                UUID.randomUUID();

        var profile =
                new StaffProfile(
                        staffId,
                        userId,
                        tenantId,
                        StaffStatus.ACTIVE);

        assertThat(profile.staffId())
                .isEqualTo(staffId);

        assertThat(profile.userId())
                .isEqualTo(userId);

        assertThat(profile.tenantId())
                .isEqualTo(tenantId);

        assertThat(profile.isActive())
                .isTrue();
    }

    @Test
    void sameUserCanParticipateIndependentlyAcrossTenants() {

        var userId =
                UUID.randomUUID();

        var tenantA =
                new StaffProfile(
                        UUID.randomUUID(),
                        userId,
                        UUID.randomUUID(),
                        StaffStatus.ACTIVE);

        var tenantB =
                new StaffProfile(
                        UUID.randomUUID(),
                        userId,
                        UUID.randomUUID(),
                        StaffStatus.INACTIVE);

        assertThat(tenantA.userId())
                .isEqualTo(
                        tenantB.userId());

        assertThat(tenantA.tenantId())
                .isNotEqualTo(
                        tenantB.tenantId());

        assertThat(tenantA.isActive())
                .isTrue();

        assertThat(tenantB.isActive())
                .isFalse();
    }

    @Test
    void rejectsIncompleteStaffProfile() {

        var id =
                UUID.randomUUID();

        assertThatThrownBy(() ->
                new StaffProfile(
                        null,
                        id,
                        id,
                        StaffStatus.ACTIVE))
                .isInstanceOf(
                        IllegalArgumentException.class);

        assertThatThrownBy(() ->
                new StaffProfile(
                        id,
                        null,
                        id,
                        StaffStatus.ACTIVE))
                .isInstanceOf(
                        IllegalArgumentException.class);

        assertThatThrownBy(() ->
                new StaffProfile(
                        id,
                        id,
                        null,
                        StaffStatus.ACTIVE))
                .isInstanceOf(
                        IllegalArgumentException.class);

        assertThatThrownBy(() ->
                new StaffProfile(
                        id,
                        id,
                        id,
                        null))
                .isInstanceOf(
                        IllegalArgumentException.class);
    }
}
