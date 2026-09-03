package io.github.piresrenan.orderhub.workforce.domain.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.github.piresrenan.orderhub.authorization.domain.model.AuthorityBand;
import io.github.piresrenan.orderhub.authorization.domain.model.PermissionCode;
import io.github.piresrenan.orderhub.authorization.domain.model.PermissionEnvelope;
import io.github.piresrenan.orderhub.workforce.domain.model.Department;
import io.github.piresrenan.orderhub.workforce.domain.model.JobPosition;
import io.github.piresrenan.orderhub.workforce.domain.model.PositionChangeType;
import io.github.piresrenan.orderhub.workforce.domain.model.StaffPlacement;
import io.github.piresrenan.orderhub.workforce.domain.model.StaffProfile;
import io.github.piresrenan.orderhub.workforce.domain.model.StaffStatus;

class WorkforceAuthorityLifecycleTest {

    private final WorkforceAuthorityResolver authorityResolver =
            new WorkforceAuthorityResolver();

    private final PositionChangePolicy positionChangePolicy =
            new PositionChangePolicy();

    @Test
    void placementRequiresStaffDepartmentAndPositionInsideSameTenant() {

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
                        "OPERATOR",
                        AuthorityBand.OPERATIONAL,
                        Set.of(
                                PermissionCode.ORDERS_VIEW));

        var placement =
                StaffPlacement.assign(
                        staff,
                        department,
                        position);

        assertThat(placement.tenantId())
                .isEqualTo(
                        tenantId);

        assertThat(placement.staffId())
                .isEqualTo(
                        staff.staffId());

        assertThat(placement.departmentId())
                .isEqualTo(
                        department.departmentId());

        assertThat(placement.positionId())
                .isEqualTo(
                        position.positionId());

        assertThatThrownBy(() ->
                StaffPlacement.assign(
                        staff,
                        department(
                                UUID.randomUUID()),
                        position))
                .isInstanceOf(
                        IllegalArgumentException.class)
                .hasMessageContaining(
                        "Tenant");
    }

    @Test
    void activeStaffResolvesExactPositionAuthorityWithoutInventingPermissions() {

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
                        "LIMITED_MANAGER",
                        AuthorityBand.MANAGEMENT,
                        Set.of(
                                PermissionCode.CATALOG_VIEW));

        var placement =
                StaffPlacement.assign(
                        staff,
                        department,
                        position);

        var authority =
                authorityResolver.resolve(
                        staff,
                        placement,
                        position);

        assertThat(
                authority.authorityBand())
                .contains(
                        AuthorityBand.MANAGEMENT);

        assertThat(
                authority.permissionEnvelope()
                        .allows(
                                PermissionCode.CATALOG_VIEW))
                .isTrue();

        assertThat(
                authority.permissionEnvelope()
                        .allows(
                                PermissionCode.TENANT_MEMBERS_MANAGE))
                .isFalse();
    }

    @Test
    void inactiveStaffResolvesNoWorkforceAuthority() {

        var tenantId =
                UUID.randomUUID();

        var staff =
                staff(
                        tenantId,
                        StaffStatus.INACTIVE);

        var department =
                department(
                        tenantId);

        var position =
                position(
                        tenantId,
                        "TENANT_MANAGER",
                        AuthorityBand.TENANT_GOVERNANCE,
                        Set.of(
                                PermissionCode.TENANT_MEMBERS_MANAGE,
                                PermissionCode.TENANT_PRIVILEGED_ROLES_ASSIGN));

        var placement =
                StaffPlacement.assign(
                        staff,
                        department,
                        position);

        var authority =
                authorityResolver.resolve(
                        staff,
                        placement,
                        position);

        assertThat(
                authority.authorityBand())
                .isEmpty();

        assertThat(
                authority.permissionEnvelope()
                        .permissions())
                .isEmpty();
    }

    @Test
    void positionChangeExplicitlyClassifiesPromotionDemotionAndLateralMovement() {

        var tenantId =
                UUID.randomUUID();

        var staff =
                staff(
                        tenantId,
                        StaffStatus.ACTIVE);

        var department =
                department(
                        tenantId);

        var operational =
                position(
                        tenantId,
                        "ORDER_OPERATOR",
                        AuthorityBand.OPERATIONAL,
                        Set.of(
                                PermissionCode.ORDERS_VIEW));

        var supervisory =
                position(
                        tenantId,
                        "ORDER_SUPERVISOR",
                        AuthorityBand.SUPERVISORY,
                        Set.of(
                                PermissionCode.ORDERS_VIEW,
                                PermissionCode.ORDERS_APPROVE));

        var lateral =
                position(
                        tenantId,
                        "INVENTORY_OPERATOR",
                        AuthorityBand.OPERATIONAL,
                        Set.of(
                                PermissionCode.INVENTORY_VIEW));

        var operationalPlacement =
                StaffPlacement.assign(
                        staff,
                        department,
                        operational);

        var supervisoryPlacement =
                StaffPlacement.assign(
                        staff,
                        department,
                        supervisory);

        var promotion =
                positionChangePolicy.evaluate(
                        staff,
                        operationalPlacement,
                        operational,
                        supervisory);

        var demotion =
                positionChangePolicy.evaluate(
                        staff,
                        supervisoryPlacement,
                        supervisory,
                        operational);

        var lateralMove =
                positionChangePolicy.evaluate(
                        staff,
                        operationalPlacement,
                        operational,
                        lateral);

        assertThat(promotion.type())
                .isEqualTo(
                        PositionChangeType.PROMOTION);

        assertThat(promotion.beforeAuthorityBand())
                .isEqualTo(
                        AuthorityBand.OPERATIONAL);

        assertThat(promotion.afterAuthorityBand())
                .isEqualTo(
                        AuthorityBand.SUPERVISORY);

        assertThat(demotion.type())
                .isEqualTo(
                        PositionChangeType.DEMOTION);

        assertThat(lateralMove.type())
                .isEqualTo(
                        PositionChangeType.LATERAL);
    }

    @Test
    void contractedWorkforceCeilingNeutralizesStaleRoleAndAllowPermissions() {

        var contractedCeiling =
                PermissionEnvelope.of(
                        Set.of(
                                PermissionCode.CATALOG_VIEW));

        var staleAuthorizationPermissions =
                Set.of(
                        PermissionCode.CATALOG_VIEW,
                        PermissionCode.TENANT_MEMBERS_MANAGE,
                        PermissionCode.TENANT_PRIVILEGED_ROLES_ASSIGN);

        var effective =
                WorkforcePermissionCeiling.constrain(
                        contractedCeiling,
                        staleAuthorizationPermissions);

        assertThat(
                effective.permissions())
                .containsExactly(
                        PermissionCode.CATALOG_VIEW);

        assertThat(
                effective.allows(
                        PermissionCode.TENANT_MEMBERS_MANAGE))
                .isFalse();

        assertThat(
                effective.allows(
                        PermissionCode.TENANT_PRIVILEGED_ROLES_ASSIGN))
                .isFalse();
    }

    private static StaffProfile staff(
            UUID tenantId,
            StaffStatus status) {

        return new StaffProfile(
                UUID.randomUUID(),
                UUID.randomUUID(),
                tenantId,
                status);
    }

    private static Department department(
            UUID tenantId) {

        return new Department(
                UUID.randomUUID(),
                tenantId,
                "OPS",
                "Operations");
    }

    private static JobPosition position(
            UUID tenantId,
            String code,
            AuthorityBand authorityBand,
            Set<PermissionCode> permissions) {

        return new JobPosition(
                UUID.randomUUID(),
                tenantId,
                code,
                code,
                authorityBand,
                PermissionEnvelope.of(
                        permissions));
    }
}
