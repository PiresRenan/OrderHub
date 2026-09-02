package io.github.piresrenan.orderhub.authorization.domain.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;

import org.junit.jupiter.api.Test;

import io.github.piresrenan.orderhub.authorization.domain.model.AuthorityBand;
import io.github.piresrenan.orderhub.authorization.domain.model.AuthorizationDecision;
import io.github.piresrenan.orderhub.authorization.domain.model.AuthorizationPersona;
import io.github.piresrenan.orderhub.authorization.domain.model.PermissionCode;
import io.github.piresrenan.orderhub.authorization.domain.model.PermissionEnvelope;
import io.github.piresrenan.orderhub.authorization.domain.model.RoleDefinition;
import io.github.piresrenan.orderhub.authorization.domain.model.RoleMutability;

class TenantCustomRoleMutationPolicyTest {

    private final TenantCustomRoleMutationPolicy policy =
            new TenantCustomRoleMutationPolicy();

    @Test
    void rejectsSystemLockedRoleMutation() {
        var current =
                role(
                        "SYSTEM_GOVERNANCE",
                        AuthorityBand.TENANT_GOVERNANCE,
                        RoleMutability.SYSTEM_LOCKED,
                        Set.of(
                                PermissionCode.AUDIT_VIEW),
                        Set.of(
                                PermissionCode.AUDIT_VIEW));

        var replacement =
                role(
                        "SYSTEM_GOVERNANCE",
                        AuthorityBand.TENANT_GOVERNANCE,
                        RoleMutability.SYSTEM_LOCKED,
                        Set.of(),
                        Set.of(
                                PermissionCode.AUDIT_VIEW));

        assertThat(
                policy.evaluate(
                        current,
                        replacement,
                        envelope(
                                PermissionCode.AUDIT_VIEW)))
                .isEqualTo(
                        AuthorizationDecision.DENY);
    }

    @Test
    void rejectsTenantProtectedAndBuiltinRoleMutation() {
        var protectedCurrent =
                role(
                        "TENANT_ADMINISTRATOR",
                        AuthorityBand.TENANT_GOVERNANCE,
                        RoleMutability.TENANT_PROTECTED,
                        Set.of(
                                PermissionCode.TENANT_MEMBERS_MANAGE),
                        Set.of(
                                PermissionCode.TENANT_MEMBERS_MANAGE));

        var protectedReplacement =
                role(
                        "TENANT_ADMINISTRATOR",
                        AuthorityBand.TENANT_GOVERNANCE,
                        RoleMutability.TENANT_PROTECTED,
                        Set.of(),
                        Set.of(
                                PermissionCode.TENANT_MEMBERS_MANAGE));

        var builtinCurrent =
                role(
                        "ORDER_MANAGER",
                        AuthorityBand.MANAGEMENT,
                        RoleMutability.BUILTIN_FUNCTIONAL,
                        Set.of(
                                PermissionCode.ORDERS_MANAGE),
                        Set.of(
                                PermissionCode.ORDERS_MANAGE));

        var builtinReplacement =
                role(
                        "ORDER_MANAGER",
                        AuthorityBand.MANAGEMENT,
                        RoleMutability.BUILTIN_FUNCTIONAL,
                        Set.of(),
                        Set.of(
                                PermissionCode.ORDERS_MANAGE));

        assertThat(
                policy.evaluate(
                        protectedCurrent,
                        protectedReplacement,
                        envelope(
                                PermissionCode.TENANT_MEMBERS_MANAGE)))
                .isEqualTo(
                        AuthorizationDecision.DENY);

        assertThat(
                policy.evaluate(
                        builtinCurrent,
                        builtinReplacement,
                        envelope(
                                PermissionCode.ORDERS_MANAGE)))
                .isEqualTo(
                        AuthorizationDecision.DENY);
    }

    @Test
    void rejectsCustomRoleIdentityOrAuthorityBandRewrite() {
        var current =
                role(
                        "CUSTOM_ORDER_REVIEW",
                        AuthorityBand.COORDINATION,
                        RoleMutability.TENANT_CUSTOM,
                        Set.of(
                                PermissionCode.ORDERS_VIEW),
                        Set.of(
                                PermissionCode.ORDERS_VIEW,
                                PermissionCode.ORDERS_APPROVE));

        var rewrittenCode =
                role(
                        "CUSTOM_ORDER_ADMIN",
                        AuthorityBand.COORDINATION,
                        RoleMutability.TENANT_CUSTOM,
                        Set.of(
                                PermissionCode.ORDERS_VIEW),
                        Set.of(
                                PermissionCode.ORDERS_VIEW,
                                PermissionCode.ORDERS_APPROVE));

        var promotedBand =
                role(
                        "CUSTOM_ORDER_REVIEW",
                        AuthorityBand.MANAGEMENT,
                        RoleMutability.TENANT_CUSTOM,
                        Set.of(
                                PermissionCode.ORDERS_VIEW),
                        Set.of(
                                PermissionCode.ORDERS_VIEW,
                                PermissionCode.ORDERS_APPROVE));

        var actorEnvelope =
                envelope(
                        PermissionCode.ORDERS_VIEW,
                        PermissionCode.ORDERS_APPROVE);

        assertThat(
                policy.evaluate(
                        current,
                        rewrittenCode,
                        actorEnvelope))
                .isEqualTo(
                        AuthorizationDecision.DENY);

        assertThat(
                policy.evaluate(
                        current,
                        promotedBand,
                        actorEnvelope))
                .isEqualTo(
                        AuthorizationDecision.DENY);
    }

    @Test
    void rejectsCustomRoleEnvelopeWideningOrPermissionsOutsideActorBoundary() {
        var current =
                role(
                        "CUSTOM_ORDER_REVIEW",
                        AuthorityBand.COORDINATION,
                        RoleMutability.TENANT_CUSTOM,
                        Set.of(
                                PermissionCode.ORDERS_VIEW),
                        Set.of(
                                PermissionCode.ORDERS_VIEW,
                                PermissionCode.ORDERS_APPROVE));

        var outsideActorBoundary =
                role(
                        "CUSTOM_ORDER_REVIEW",
                        AuthorityBand.COORDINATION,
                        RoleMutability.TENANT_CUSTOM,
                        Set.of(
                                PermissionCode.ORDERS_VIEW,
                                PermissionCode.ORDERS_APPROVE),
                        Set.of(
                                PermissionCode.ORDERS_VIEW,
                                PermissionCode.ORDERS_APPROVE));

        var widenedDefinitionEnvelope =
                role(
                        "CUSTOM_ORDER_REVIEW",
                        AuthorityBand.COORDINATION,
                        RoleMutability.TENANT_CUSTOM,
                        Set.of(
                                PermissionCode.ORDERS_VIEW),
                        Set.of(
                                PermissionCode.ORDERS_VIEW,
                                PermissionCode.ORDERS_APPROVE,
                                PermissionCode.ORDERS_MANAGE));

        assertThat(
                policy.evaluate(
                        current,
                        outsideActorBoundary,
                        envelope(
                                PermissionCode.ORDERS_VIEW)))
                .isEqualTo(
                        AuthorizationDecision.DENY);

        assertThat(
                policy.evaluate(
                        current,
                        widenedDefinitionEnvelope,
                        envelope(
                                PermissionCode.ORDERS_VIEW,
                                PermissionCode.ORDERS_APPROVE,
                                PermissionCode.ORDERS_MANAGE)))
                .isEqualTo(
                        AuthorizationDecision.DENY);
    }

    @Test
    void allowsBoundedPermissionReductionOfTenantCustomRole() {
        var current =
                role(
                        "CUSTOM_ORDER_REVIEW",
                        AuthorityBand.COORDINATION,
                        RoleMutability.TENANT_CUSTOM,
                        Set.of(
                                PermissionCode.ORDERS_VIEW,
                                PermissionCode.ORDERS_APPROVE),
                        Set.of(
                                PermissionCode.ORDERS_VIEW,
                                PermissionCode.ORDERS_APPROVE));

        var replacement =
                role(
                        "CUSTOM_ORDER_REVIEW",
                        AuthorityBand.COORDINATION,
                        RoleMutability.TENANT_CUSTOM,
                        Set.of(
                                PermissionCode.ORDERS_VIEW),
                        Set.of(
                                PermissionCode.ORDERS_VIEW,
                                PermissionCode.ORDERS_APPROVE));

        var decision =
                policy.evaluate(
                        current,
                        replacement,
                        envelope(
                                PermissionCode.ORDERS_VIEW,
                                PermissionCode.ORDERS_APPROVE));

        assertThat(decision)
                .isEqualTo(
                        AuthorizationDecision.ALLOW);
    }

    private static RoleDefinition role(
            String code,
            AuthorityBand authorityBand,
            RoleMutability mutability,
            Set<PermissionCode> permissions,
            Set<PermissionCode> definitionEnvelope) {

        return new RoleDefinition(
                code,
                AuthorizationPersona.STAFF,
                authorityBand,
                mutability,
                permissions,
                PermissionEnvelope.of(
                        definitionEnvelope));
    }

    private static PermissionEnvelope envelope(
            PermissionCode... permissions) {

        return PermissionEnvelope.of(
                Set.of(
                        permissions));
    }
}