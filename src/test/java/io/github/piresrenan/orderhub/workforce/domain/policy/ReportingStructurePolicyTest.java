package io.github.piresrenan.orderhub.workforce.domain.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.github.piresrenan.orderhub.workforce.domain.model.ReportingRelationship;
import io.github.piresrenan.orderhub.workforce.domain.model.StaffProfile;
import io.github.piresrenan.orderhub.workforce.domain.model.StaffStatus;

class ReportingStructurePolicyTest {

    private final ReportingStructurePolicy policy =
            new ReportingStructurePolicy();

    @Test
    void allowsAcyclicReportingInsideOneTenant() {

        var tenantId =
                UUID.randomUUID();

        var supervisor =
                staff(
                        tenantId);

        var subordinate =
                staff(
                        tenantId);

        var relationship =
                policy.establish(
                        supervisor,
                        subordinate,
                        List.of());

        assertThat(relationship.tenantId())
                .isEqualTo(tenantId);

        assertThat(relationship.supervisorStaffId())
                .isEqualTo(
                        supervisor.staffId());

        assertThat(relationship.subordinateStaffId())
                .isEqualTo(
                        subordinate.staffId());
    }

    @Test
    void rejectsSelfSupervision() {

        var profile =
                staff(
                        UUID.randomUUID());

        assertThatThrownBy(() ->
                policy.establish(
                        profile,
                        profile,
                        List.of()))
                .isInstanceOf(
                        IllegalArgumentException.class);
    }

    @Test
    void rejectsCrossTenantReporting() {

        assertThatThrownBy(() ->
                policy.establish(
                        staff(
                                UUID.randomUUID()),
                        staff(
                                UUID.randomUUID()),
                        List.of()))
                .isInstanceOf(
                        IllegalArgumentException.class);
    }

    @Test
    void rejectsInactiveSupervisor() {

        var tenantId =
                UUID.randomUUID();

        var supervisor =
                new StaffProfile(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        tenantId,
                        StaffStatus.INACTIVE);

        assertThatThrownBy(() ->
                policy.establish(
                        supervisor,
                        staff(
                                tenantId),
                        List.of()))
                .isInstanceOf(
                        IllegalArgumentException.class)
                .hasMessageContaining(
                        "ACTIVE");
    }

    @Test
    void rejectsDuplicateReportingRelationship() {

        var tenantId =
                UUID.randomUUID();

        var supervisor =
                staff(
                        tenantId);

        var subordinate =
                staff(
                        tenantId);

        var existingRelationship =
                new ReportingRelationship(
                        tenantId,
                        supervisor.staffId(),
                        subordinate.staffId());

        assertThatThrownBy(() ->
                policy.establish(
                        supervisor,
                        subordinate,
                        List.of(
                                existingRelationship)))
                .isInstanceOf(
                        IllegalArgumentException.class)
                .hasMessageContaining(
                        "already exists");
    }

    @Test
    void rejectsReportingCycle() {

        var tenantId =
                UUID.randomUUID();

        var staffA =
                staff(
                        tenantId);

        var staffB =
                staff(
                        tenantId);

        var staffC =
                staff(
                        tenantId);

        var existing =
                List.of(
                        new ReportingRelationship(
                                tenantId,
                                staffA.staffId(),
                                staffB.staffId()),
                        new ReportingRelationship(
                                tenantId,
                                staffB.staffId(),
                                staffC.staffId()));

        assertThatThrownBy(() ->
                policy.establish(
                        staffC,
                        staffA,
                        existing))
                .isInstanceOf(
                        IllegalArgumentException.class)
                .hasMessageContaining(
                        "cycle");
    }

    private static StaffProfile staff(
            UUID tenantId) {

        return new StaffProfile(
                UUID.randomUUID(),
                UUID.randomUUID(),
                tenantId,
                StaffStatus.ACTIVE);
    }
}
