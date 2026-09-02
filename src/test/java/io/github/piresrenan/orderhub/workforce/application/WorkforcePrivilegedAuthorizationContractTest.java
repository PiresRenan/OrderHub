package io.github.piresrenan.orderhub.workforce.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.github.piresrenan.orderhub.authorization.domain.model.AuthorityBand;
import io.github.piresrenan.orderhub.authorization.domain.model.PermissionCode;
import io.github.piresrenan.orderhub.authorization.domain.model.PermissionEnvelope;
import io.github.piresrenan.orderhub.workforce.application.model.PrivilegedPositionChangeRequest;
import io.github.piresrenan.orderhub.workforce.application.port.in.ResolveEffectiveWorkforceAuthorityUseCase;
import io.github.piresrenan.orderhub.workforce.application.service.BoundedWorkforceAuthorizationService;
import io.github.piresrenan.orderhub.workforce.domain.model.Department;
import io.github.piresrenan.orderhub.workforce.domain.model.JobPosition;
import io.github.piresrenan.orderhub.workforce.domain.model.PositionChange;
import io.github.piresrenan.orderhub.workforce.domain.model.PositionChangeType;
import io.github.piresrenan.orderhub.workforce.domain.model.StaffPlacement;
import io.github.piresrenan.orderhub.workforce.domain.model.StaffProfile;
import io.github.piresrenan.orderhub.workforce.domain.model.StaffStatus;
import io.github.piresrenan.orderhub.workforce.domain.model.WorkforceMutationDecision;
import io.github.piresrenan.orderhub.workforce.domain.policy.PrivilegedWorkforceMutationPolicy;
import io.github.piresrenan.orderhub.workforce.domain.policy.WorkforceAuthorityResolver;

class WorkforcePrivilegedAuthorizationContractTest {

    @Test
    void effectiveWorkforceAuthorityIsAvailableThroughAnApplicationPort() {

        var tenantId =
                UUID.randomUUID();

        var staff =
                staff(
                        tenantId,
                        StaffStatus.ACTIVE);

        var department =
                department(
                        tenantId);

        var position =
                position(
                        tenantId,
                        "OPS",
                        AuthorityBand.OPERATIONAL,
                        PermissionCode.CATALOG_VIEW);

        var placement =
                StaffPlacement.assign(
                        staff,
                        department,
                        position);

        ResolveEffectiveWorkforceAuthorityUseCase useCase =
                new WorkforceAuthorityResolver()::resolve;

        var authority =
                useCase.resolve(
                        staff,
                        placement,
                        position);

        assertThat(
                authority.authorityBand())
                .contains(
                        AuthorityBand.OPERATIONAL);

        assertThat(
                authority.permissionEnvelope()
                        .allows(
                                PermissionCode.CATALOG_VIEW))
                .isTrue();
    }

    @Test
    void authorizationCandidateIsContractedByCurrentWorkforceCeiling() {

        var candidate =
                PermissionEnvelope.of(
                        Set.of(
                                PermissionCode.CATALOG_VIEW,
                                PermissionCode.ORDERS_MANAGE));

        var workforceAuthority =
                io.github.piresrenan.orderhub.workforce.domain.model.EffectiveWorkforceAuthority.active(
                        AuthorityBand.OPERATIONAL,
                        PermissionEnvelope.of(
                                Set.of(
                                        PermissionCode.CATALOG_VIEW)));

        var service =
                new BoundedWorkforceAuthorizationService();

        var bounded =
                service.constrain(
                        candidate,
                        workforceAuthority);

        assertThat(
                bounded.allows(
                        PermissionCode.CATALOG_VIEW))
                .isTrue();

        assertThat(
                bounded.allows(
                        PermissionCode.ORDERS_MANAGE))
                .isFalse();

        assertThat(
                bounded.permissions())
                .containsExactly(
                        PermissionCode.CATALOG_VIEW);
    }

    @Test
    void privilegedPositionChangeBindsExactActorTargetTenantAndAuthorizationOutcome() {

        var tenantId =
                UUID.randomUUID();

        var actor =
                staff(
                        tenantId,
                        StaffStatus.ACTIVE);

        var target =
                staff(
                        tenantId,
                        StaffStatus.ACTIVE);

        var change =
                positionChange(
                        target.staffId(),
                        tenantId);

        var allowedRequest =
                new PrivilegedPositionChangeRequest(
                        actor.staffId(),
                        target.staffId(),
                        tenantId,
                        change,
                        true);

        assertThat(
                evaluate(
                        allowedRequest,
                        actor,
                        target))
                .isEqualTo(
                        WorkforceMutationDecision.ALLOW);

        var wrongActorRequest =
                new PrivilegedPositionChangeRequest(
                        UUID.randomUUID(),
                        target.staffId(),
                        tenantId,
                        change,
                        true);

        assertThat(
                evaluate(
                        wrongActorRequest,
                        actor,
                        target))
                .isEqualTo(
                        WorkforceMutationDecision.DENY);

        var otherTarget =
                staff(
                        tenantId,
                        StaffStatus.ACTIVE);

        assertThat(
                evaluate(
                        allowedRequest,
                        actor,
                        otherTarget))
                .isEqualTo(
                        WorkforceMutationDecision.DENY);

        var crossTenantActor =
                staff(
                        UUID.randomUUID(),
                        StaffStatus.ACTIVE);

        var crossTenantRequest =
                new PrivilegedPositionChangeRequest(
                        crossTenantActor.staffId(),
                        target.staffId(),
                        tenantId,
                        change,
                        true);

        assertThat(
                evaluate(
                        crossTenantRequest,
                        crossTenantActor,
                        target))
                .isEqualTo(
                        WorkforceMutationDecision.DENY);

        var deniedRequest =
                new PrivilegedPositionChangeRequest(
                        actor.staffId(),
                        target.staffId(),
                        tenantId,
                        change,
                        false);

        assertThat(
                evaluate(
                        deniedRequest,
                        actor,
                        target))
                .isEqualTo(
                        WorkforceMutationDecision.DENY);
    }

    @Test
    void selfDirectedPrivilegedPositionChangeFailsClosed() {

        var tenantId =
                UUID.randomUUID();

        var staff =
                staff(
                        tenantId,
                        StaffStatus.ACTIVE);

        var change =
                positionChange(
                        staff.staffId(),
                        tenantId);

        var request =
                new PrivilegedPositionChangeRequest(
                        staff.staffId(),
                        staff.staffId(),
                        tenantId,
                        change,
                        true);

        var decision =
                evaluate(
                        request,
                        staff,
                        staff);

        assertThat(decision)
                .isEqualTo(
                        WorkforceMutationDecision.DENY);
    }

    @Test
    void inactiveTargetFailsClosedEvenWhenActorAuthorizationWasAllowed() {

        var tenantId =
                UUID.randomUUID();

        var actor =
                staff(
                        tenantId,
                        StaffStatus.ACTIVE);

        var target =
                staff(
                        tenantId,
                        StaffStatus.INACTIVE);

        var change =
                positionChange(
                        target.staffId(),
                        tenantId);

        var request =
                new PrivilegedPositionChangeRequest(
                        actor.staffId(),
                        target.staffId(),
                        tenantId,
                        change,
                        true);

        var decision =
                evaluate(
                        request,
                        actor,
                        target);

        assertThat(decision)
                .isEqualTo(
                        WorkforceMutationDecision.DENY);
    }

    private WorkforceMutationDecision evaluate(
            PrivilegedPositionChangeRequest request,
            StaffProfile actor,
            StaffProfile target) {

        return new PrivilegedWorkforceMutationPolicy()
                .evaluate(
                        request.actorStaffId(),
                        request.targetStaffId(),
                        request.tenantId(),
                        actor,
                        target,
                        request.positionChange(),
                        request.actorPrivilegedAuthorizationAllowed());
    }
    private StaffProfile staff(
            UUID tenantId,
            StaffStatus status) {

        return new StaffProfile(
                UUID.randomUUID(),
                UUID.randomUUID(),
                tenantId,
                status);
    }

    private Department department(
            UUID tenantId) {

        return new Department(
                UUID.randomUUID(),
                tenantId,
                "OPS",
                "Operations");
    }

    private JobPosition position(
            UUID tenantId,
            String code,
            AuthorityBand authorityBand,
            PermissionCode... permissions) {

        return new JobPosition(
                UUID.randomUUID(),
                tenantId,
                code,
                code,
                authorityBand,
                PermissionEnvelope.of(
                        Set.of(
                                permissions)));
    }

    private PositionChange positionChange(
            UUID targetStaffId,
            UUID tenantId) {

        return new PositionChange(
                targetStaffId,
                tenantId,
                UUID.randomUUID(),
                UUID.randomUUID(),
                AuthorityBand.OPERATIONAL,
                AuthorityBand.MANAGEMENT,
                PermissionEnvelope.of(
                        Set.of(
                                PermissionCode.CATALOG_VIEW)),
                PermissionEnvelope.of(
                        Set.of(
                                PermissionCode.CATALOG_VIEW,
                                PermissionCode.CATALOG_MANAGE)),
                PositionChangeType.PROMOTION);
    }
}
