package io.github.piresrenan.orderhub.authorization.domain.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.EnumSet;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.piresrenan.orderhub.authorization.domain.model.AuthorizationDecision;
import io.github.piresrenan.orderhub.authorization.domain.model.AuthorizationPersona;
import io.github.piresrenan.orderhub.authorization.domain.model.AuthorityBand;
import io.github.piresrenan.orderhub.authorization.domain.model.PermissionCode;
import io.github.piresrenan.orderhub.authorization.domain.model.PermissionEnvelope;
import io.github.piresrenan.orderhub.authorization.domain.model.PermissionOverride;
import io.github.piresrenan.orderhub.authorization.domain.model.RoleDefinition;
import io.github.piresrenan.orderhub.authorization.domain.model.RoleMutability;

class EffectivePermissionEvaluatorTest {

    private final EffectivePermissionEvaluator evaluator =
            new EffectivePermissionEvaluator();

    @Test
    void deniesByDefaultWhenNeitherRoleNorOverrideGrantsThePermission() {

        var role =
                role(
                        EnumSet.of(
                                PermissionCode.INVENTORY_VIEW));

        var actorEnvelope =
                PermissionEnvelope.of(
                        EnumSet.of(
                                PermissionCode.INVENTORY_VIEW,
                                PermissionCode.INVENTORY_ADJUST));

        assertThat(
                evaluator.evaluate(
                        role,
                        actorEnvelope,
                        List.of(),
                        PermissionCode.INVENTORY_ADJUST))
                .isEqualTo(
                        AuthorizationDecision.DENY);
    }

    @Test
    void rolePermissionAllowsOnlyWhenTheActorEnvelopeAlsoAllowsIt() {

        var role =
                role(
                        EnumSet.of(
                                PermissionCode.INVENTORY_VIEW,
                                PermissionCode.INVENTORY_ADJUST));

        var broadEnvelope =
                PermissionEnvelope.of(
                        EnumSet.of(
                                PermissionCode.INVENTORY_VIEW,
                                PermissionCode.INVENTORY_ADJUST));

        var restrictedEnvelope =
                PermissionEnvelope.of(
                        EnumSet.of(
                                PermissionCode.INVENTORY_VIEW));

        assertThat(
                evaluator.evaluate(
                        role,
                        broadEnvelope,
                        List.of(),
                        PermissionCode.INVENTORY_ADJUST))
                .isEqualTo(
                        AuthorizationDecision.ALLOW);

        assertThat(
                evaluator.evaluate(
                        role,
                        restrictedEnvelope,
                        List.of(),
                        PermissionCode.INVENTORY_ADJUST))
                .isEqualTo(
                        AuthorizationDecision.DENY);
    }

    @Test
    void explicitDenyOverrideWinsOverRolePermission() {

        var permissions =
                EnumSet.of(
                        PermissionCode.INVENTORY_VIEW,
                        PermissionCode.INVENTORY_ADJUST);

        var role =
                role(
                        permissions);

        var envelope =
                PermissionEnvelope.of(
                        permissions);

        assertThat(
                evaluator.evaluate(
                        role,
                        envelope,
                        List.of(
                                PermissionOverride.deny(
                                        PermissionCode.INVENTORY_ADJUST)),
                        PermissionCode.INVENTORY_ADJUST))
                .isEqualTo(
                        AuthorizationDecision.DENY);
    }

    @Test
    void allowOverrideInsideEnvelopeCanAddAnOtherwiseAbsentPermission() {

        var role =
                role(
                        EnumSet.of(
                                PermissionCode.INVENTORY_VIEW));

        var envelope =
                PermissionEnvelope.of(
                        EnumSet.of(
                                PermissionCode.INVENTORY_VIEW,
                                PermissionCode.INVENTORY_ADJUST));

        var override =
                PermissionOverride.allow(
                        PermissionCode.INVENTORY_ADJUST,
                        envelope);

        assertThat(
                evaluator.evaluate(
                        role,
                        envelope,
                        List.of(
                                override),
                        PermissionCode.INVENTORY_ADJUST))
                .isEqualTo(
                        AuthorizationDecision.ALLOW);
    }

    @Test
    void allowOverrideOutsideEnvelopeIsRejectedBeforeEvaluation() {

        var envelope =
                PermissionEnvelope.of(
                        EnumSet.of(
                                PermissionCode.INVENTORY_VIEW));

        assertThatThrownBy(() ->
                PermissionOverride.allow(
                        PermissionCode.INVENTORY_POLICY_MANAGE,
                        envelope))
                .isInstanceOf(
                        IllegalArgumentException.class)
                .hasMessage(
                        "ALLOW override exceeds the permission envelope");
    }

    @Test
    void managementAuthorityDoesNotBlindlyInheritOperationalPermissions() {

        var definitionEnvelope =
                PermissionEnvelope.of(
                        EnumSet.of(
                                PermissionCode.INVENTORY_POLICY_MANAGE));

        var managementRole =
                new RoleDefinition(
                        "INVENTORY_MANAGER",
                        AuthorizationPersona.STAFF,
                        AuthorityBand.MANAGEMENT,
                        RoleMutability.BUILTIN_FUNCTIONAL,
                        EnumSet.of(
                                PermissionCode.INVENTORY_POLICY_MANAGE),
                        definitionEnvelope);

        /*
         * Even though the actor envelope itself could allow operational work,
         * MANAGEMENT rank alone must not manufacture INVENTORY_ADJUST.
         */
        var actorEnvelope =
                PermissionEnvelope.of(
                        EnumSet.of(
                                PermissionCode.INVENTORY_POLICY_MANAGE,
                                PermissionCode.INVENTORY_ADJUST));

        assertThat(
                evaluator.evaluate(
                        managementRole,
                        actorEnvelope,
                        List.of(),
                        PermissionCode.INVENTORY_ADJUST))
                .isEqualTo(
                        AuthorizationDecision.DENY);
    }

    private static RoleDefinition role(
            EnumSet<PermissionCode> permissions) {

        var definitionEnvelope =
                PermissionEnvelope.of(
                        permissions);

        return new RoleDefinition(
                "INVENTORY_OPERATOR",
                AuthorizationPersona.STAFF,
                AuthorityBand.OPERATIONAL,
                RoleMutability.BUILTIN_FUNCTIONAL,
                permissions,
                definitionEnvelope);
    }
}
