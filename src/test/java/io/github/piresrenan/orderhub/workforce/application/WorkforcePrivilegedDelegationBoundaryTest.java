package io.github.piresrenan.orderhub.workforce.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.github.piresrenan.orderhub.authorization.domain.model.AuthorityBand;
import io.github.piresrenan.orderhub.authorization.domain.model.PermissionCode;
import io.github.piresrenan.orderhub.authorization.domain.model.PermissionEnvelope;
import io.github.piresrenan.orderhub.workforce.application.model.PrivilegedPositionChangeRequest;
import io.github.piresrenan.orderhub.workforce.application.service.PrivilegedWorkforceMutationAuthorizationService;
import io.github.piresrenan.orderhub.workforce.domain.model.EffectiveWorkforceAuthority;
import io.github.piresrenan.orderhub.workforce.domain.model.PositionChange;
import io.github.piresrenan.orderhub.workforce.domain.model.PositionChangeType;
import io.github.piresrenan.orderhub.workforce.domain.model.StaffProfile;
import io.github.piresrenan.orderhub.workforce.domain.model.StaffStatus;
import io.github.piresrenan.orderhub.workforce.domain.model.WorkforceMutationDecision;

class WorkforcePrivilegedDelegationBoundaryTest {

    @Test
    void authorizedActorWithinBandAndDelegationEnvelopeMayChangePosition() {

        var fixture =
                fixture(
                        AuthorityBand.TENANT_GOVERNANCE);

        var actorAuthority =
                EffectiveWorkforceAuthority.active(
                        AuthorityBand.TENANT_GOVERNANCE,
                        PermissionEnvelope.of(
                                Set.of(
                                        PermissionCode.CATALOG_VIEW)));

        var delegationEnvelope =
                PermissionEnvelope.of(
                        Set.of(
                                PermissionCode.CATALOG_VIEW,
                                PermissionCode.CATALOG_MANAGE));

        assertThat(
                authorize(
                        fixture,
                        actorAuthority,
                        delegationEnvelope))
                .isEqualTo(
                        WorkforceMutationDecision.ALLOW);
    }

    @Test
    void actorCannotDelegateAPositionAboveCurrentWorkforceAuthorityBand() {

        var fixture =
                fixture(
                        AuthorityBand.TENANT_GOVERNANCE);

        var actorAuthority =
                EffectiveWorkforceAuthority.active(
                        AuthorityBand.MANAGEMENT,
                        PermissionEnvelope.of(
                                Set.of(
                                        PermissionCode.CATALOG_VIEW)));

        var delegationEnvelope =
                PermissionEnvelope.of(
                        Set.of(
                                PermissionCode.CATALOG_VIEW,
                                PermissionCode.CATALOG_MANAGE));

        assertThat(
                authorize(
                        fixture,
                        actorAuthority,
                        delegationEnvelope))
                .isEqualTo(
                        WorkforceMutationDecision.DENY);
    }

    @Test
    void actorCannotDelegatePermissionsOutsideExplicitDelegationEnvelope() {

        var fixture =
                fixture(
                        AuthorityBand.MANAGEMENT);

        var actorAuthority =
                EffectiveWorkforceAuthority.active(
                        AuthorityBand.TENANT_GOVERNANCE,
                        PermissionEnvelope.of(
                                Set.of(
                                        PermissionCode.CATALOG_VIEW)));

        var delegationEnvelope =
                PermissionEnvelope.of(
                        Set.of(
                                PermissionCode.CATALOG_VIEW));

        assertThat(
                authorize(
                        fixture,
                        actorAuthority,
                        delegationEnvelope))
                .isEqualTo(
                        WorkforceMutationDecision.DENY);
    }

    @Test
    void missingEffectiveWorkforceAuthorityFailsClosed() {

        var fixture =
                fixture(
                        AuthorityBand.MANAGEMENT);

        var delegationEnvelope =
                PermissionEnvelope.of(
                        Set.of(
                                PermissionCode.CATALOG_VIEW,
                                PermissionCode.CATALOG_MANAGE));

        assertThat(
                authorize(
                        fixture,
                        EffectiveWorkforceAuthority.none(),
                        delegationEnvelope))
                .isEqualTo(
                        WorkforceMutationDecision.DENY);
    }

    private WorkforceMutationDecision authorize(
            Fixture fixture,
            EffectiveWorkforceAuthority actorAuthority,
            PermissionEnvelope delegationEnvelope) {

        return new PrivilegedWorkforceMutationAuthorizationService()
                .authorize(
                        fixture.request(),
                        fixture.actor(),
                        fixture.target(),
                        actorAuthority,
                        delegationEnvelope);
    }

    private Fixture fixture(
            AuthorityBand targetAuthorityBand) {

        var tenantId =
                UUID.randomUUID();

        var actor =
                new StaffProfile(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        tenantId,
                        StaffStatus.ACTIVE);

        var target =
                new StaffProfile(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        tenantId,
                        StaffStatus.ACTIVE);

        var change =
                new PositionChange(
                        target.staffId(),
                        tenantId,
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        AuthorityBand.OPERATIONAL,
                        targetAuthorityBand,
                        PermissionEnvelope.of(
                                Set.of(
                                        PermissionCode.CATALOG_VIEW)),
                        PermissionEnvelope.of(
                                Set.of(
                                        PermissionCode.CATALOG_VIEW,
                                        PermissionCode.CATALOG_MANAGE)),
                        PositionChangeType.PROMOTION);

        var request =
                new PrivilegedPositionChangeRequest(
                        actor.staffId(),
                        target.staffId(),
                        tenantId,
                        change,
                        true);

        return new Fixture(
                actor,
                target,
                request);
    }

    private record Fixture(
            StaffProfile actor,
            StaffProfile target,
            PrivilegedPositionChangeRequest request) {
    }
}
