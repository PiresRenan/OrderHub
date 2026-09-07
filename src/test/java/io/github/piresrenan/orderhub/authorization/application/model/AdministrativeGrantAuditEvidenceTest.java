package io.github.piresrenan.orderhub.authorization.application.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.github.piresrenan.orderhub.authorization.domain.model.AdministrativeScope;
import io.github.piresrenan.orderhub.authorization.domain.model.PermissionCode;

class AdministrativeGrantAuditEvidenceTest {

    @Test
    void acceptsAppliedGrantEvidence() {

        var evidence =
                evidence(
                        AdministrativeGrantAuditAction.GRANT_PERMISSION,
                        AdministrativeGrantAuditOutcome.APPLIED,
                        false,
                        true);

        assertThat(evidence.beforeGranted())
                .isFalse();

        assertThat(evidence.afterGranted())
                .isTrue();
    }

    @Test
    void acceptsNoChangeGrantEvidence() {

        var evidence =
                evidence(
                        AdministrativeGrantAuditAction.GRANT_PERMISSION,
                        AdministrativeGrantAuditOutcome.NO_CHANGE,
                        true,
                        true);

        assertThat(evidence.beforeGranted())
                .isTrue();

        assertThat(evidence.afterGranted())
                .isTrue();
    }

    @Test
    void acceptsAppliedRevokeEvidence() {

        var evidence =
                evidence(
                        AdministrativeGrantAuditAction.REVOKE_PERMISSION,
                        AdministrativeGrantAuditOutcome.APPLIED,
                        true,
                        false);

        assertThat(evidence.beforeGranted())
                .isTrue();

        assertThat(evidence.afterGranted())
                .isFalse();
    }

    @Test
    void acceptsNoChangeRevokeEvidence() {

        var evidence =
                evidence(
                        AdministrativeGrantAuditAction.REVOKE_PERMISSION,
                        AdministrativeGrantAuditOutcome.NO_CHANGE,
                        false,
                        false);

        assertThat(evidence.beforeGranted())
                .isFalse();

        assertThat(evidence.afterGranted())
                .isFalse();
    }

    @Test
    void requiresAuditEventId() {

        assertThatThrownBy(() ->
                new AdministrativeGrantAuditEvidence(
                        null,
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        AdministrativeScope.platform(),
                        PermissionCode.PLATFORM_ORGANIZATIONS_VIEW,
                        AdministrativeGrantAuditAction.GRANT_PERMISSION,
                        AdministrativeGrantAuditOutcome.APPLIED,
                        UUID.randomUUID(),
                        false,
                        true))
                .isInstanceOf(
                        IllegalArgumentException.class)
                .hasMessage(
                        "Administrative grant audit event id is required");
    }

    @Test
    void requiresActorUserId() {

        assertThatThrownBy(() ->
                new AdministrativeGrantAuditEvidence(
                        UUID.randomUUID(),
                        null,
                        UUID.randomUUID(),
                        AdministrativeScope.platform(),
                        PermissionCode.PLATFORM_ORGANIZATIONS_VIEW,
                        AdministrativeGrantAuditAction.GRANT_PERMISSION,
                        AdministrativeGrantAuditOutcome.APPLIED,
                        UUID.randomUUID(),
                        false,
                        true))
                .isInstanceOf(
                        IllegalArgumentException.class)
                .hasMessage(
                        "Administrative grant audit actor user id is required");
    }

    @Test
    void requiresTargetUserId() {

        assertThatThrownBy(() ->
                new AdministrativeGrantAuditEvidence(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        null,
                        AdministrativeScope.platform(),
                        PermissionCode.PLATFORM_ORGANIZATIONS_VIEW,
                        AdministrativeGrantAuditAction.GRANT_PERMISSION,
                        AdministrativeGrantAuditOutcome.APPLIED,
                        UUID.randomUUID(),
                        false,
                        true))
                .isInstanceOf(
                        IllegalArgumentException.class)
                .hasMessage(
                        "Administrative grant audit target user id is required");
    }

    @Test
    void requiresAdministrativeScope() {

        assertThatThrownBy(() ->
                new AdministrativeGrantAuditEvidence(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        null,
                        PermissionCode.PLATFORM_ORGANIZATIONS_VIEW,
                        AdministrativeGrantAuditAction.GRANT_PERMISSION,
                        AdministrativeGrantAuditOutcome.APPLIED,
                        UUID.randomUUID(),
                        false,
                        true))
                .isInstanceOf(
                        IllegalArgumentException.class)
                .hasMessage(
                        "Administrative grant audit scope is required");
    }

    @Test
    void requiresPermission() {

        assertThatThrownBy(() ->
                new AdministrativeGrantAuditEvidence(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        AdministrativeScope.platform(),
                        null,
                        AdministrativeGrantAuditAction.GRANT_PERMISSION,
                        AdministrativeGrantAuditOutcome.APPLIED,
                        UUID.randomUUID(),
                        false,
                        true))
                .isInstanceOf(
                        IllegalArgumentException.class)
                .hasMessage(
                        "Administrative grant audit permission is required");
    }

    @Test
    void requiresActionOutcomeAndCorrelationIdentity() {

        var actor =
                UUID.randomUUID();

        var target =
                UUID.randomUUID();

        var scope =
                AdministrativeScope.platform();

        assertThatThrownBy(() ->
                new AdministrativeGrantAuditEvidence(
                        UUID.randomUUID(),
                        actor,
                        target,
                        scope,
                        PermissionCode.PLATFORM_ORGANIZATIONS_VIEW,
                        null,
                        AdministrativeGrantAuditOutcome.APPLIED,
                        UUID.randomUUID(),
                        false,
                        true))
                .isInstanceOf(
                        IllegalArgumentException.class)
                .hasMessage(
                        "Administrative grant audit action is required");

        assertThatThrownBy(() ->
                new AdministrativeGrantAuditEvidence(
                        UUID.randomUUID(),
                        actor,
                        target,
                        scope,
                        PermissionCode.PLATFORM_ORGANIZATIONS_VIEW,
                        AdministrativeGrantAuditAction.GRANT_PERMISSION,
                        null,
                        UUID.randomUUID(),
                        false,
                        true))
                .isInstanceOf(
                        IllegalArgumentException.class)
                .hasMessage(
                        "Administrative grant audit outcome is required");

        assertThatThrownBy(() ->
                new AdministrativeGrantAuditEvidence(
                        UUID.randomUUID(),
                        actor,
                        target,
                        scope,
                        PermissionCode.PLATFORM_ORGANIZATIONS_VIEW,
                        AdministrativeGrantAuditAction.GRANT_PERMISSION,
                        AdministrativeGrantAuditOutcome.APPLIED,
                        null,
                        false,
                        true))
                .isInstanceOf(
                        IllegalArgumentException.class)
                .hasMessage(
                        "Administrative grant audit correlation id is required");
    }

    @Test
    void rejectsPermissionIncompatibleWithScope() {

        assertThatThrownBy(() ->
                new AdministrativeGrantAuditEvidence(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        AdministrativeScope.organization(
                                UUID.randomUUID()),
                        PermissionCode.PLATFORM_ORGANIZATIONS_VIEW,
                        AdministrativeGrantAuditAction.GRANT_PERMISSION,
                        AdministrativeGrantAuditOutcome.APPLIED,
                        UUID.randomUUID(),
                        false,
                        true))
                .isInstanceOf(
                        IllegalArgumentException.class)
                .hasMessage(
                        "Administrative grant audit permission is incompatible with scope");
    }

    @Test
    void rejectsImpossibleActionAndOutcomeStateCombinations() {

        assertThatThrownBy(() ->
                evidence(
                        AdministrativeGrantAuditAction.GRANT_PERMISSION,
                        AdministrativeGrantAuditOutcome.APPLIED,
                        true,
                        false))
                .isInstanceOf(
                        IllegalArgumentException.class)
                .hasMessage(
                        "Administrative grant audit action is inconsistent with after state");

        assertThatThrownBy(() ->
                evidence(
                        AdministrativeGrantAuditAction.REVOKE_PERMISSION,
                        AdministrativeGrantAuditOutcome.APPLIED,
                        false,
                        true))
                .isInstanceOf(
                        IllegalArgumentException.class)
                .hasMessage(
                        "Administrative grant audit action is inconsistent with after state");

        assertThatThrownBy(() ->
                evidence(
                        AdministrativeGrantAuditAction.GRANT_PERMISSION,
                        AdministrativeGrantAuditOutcome.APPLIED,
                        true,
                        true))
                .isInstanceOf(
                        IllegalArgumentException.class)
                .hasMessage(
                        "Applied administrative grant audit must represent a state transition");

        assertThatThrownBy(() ->
                evidence(
                        AdministrativeGrantAuditAction.REVOKE_PERMISSION,
                        AdministrativeGrantAuditOutcome.NO_CHANGE,
                        true,
                        false))
                .isInstanceOf(
                        IllegalArgumentException.class)
                .hasMessage(
                        "No-change administrative grant audit cannot represent a state transition");
    }

    private static AdministrativeGrantAuditEvidence evidence(
            AdministrativeGrantAuditAction action,
            AdministrativeGrantAuditOutcome outcome,
            boolean beforeGranted,
            boolean afterGranted) {

        return new AdministrativeGrantAuditEvidence(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                AdministrativeScope.platform(),
                PermissionCode.PLATFORM_ORGANIZATIONS_VIEW,
                action,
                outcome,
                UUID.randomUUID(),
                beforeGranted,
                afterGranted);
    }
}
