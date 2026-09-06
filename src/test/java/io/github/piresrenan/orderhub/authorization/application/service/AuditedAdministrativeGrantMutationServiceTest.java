package io.github.piresrenan.orderhub.authorization.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import io.github.piresrenan.orderhub.authorization.application.model.AdministrativeGrantAuditAction;
import io.github.piresrenan.orderhub.authorization.application.model.AdministrativeGrantAuditEvidence;
import io.github.piresrenan.orderhub.authorization.application.model.AdministrativeGrantAuditOutcome;
import io.github.piresrenan.orderhub.authorization.application.port.out.AdministrativeGrantAuditRepository;
import io.github.piresrenan.orderhub.authorization.application.port.out.AdministrativeGrantMutationResult;
import io.github.piresrenan.orderhub.authorization.application.port.out.AdministrativeGrantRepository;
import io.github.piresrenan.orderhub.authorization.application.port.out.AuthorizationPersistenceException;
import io.github.piresrenan.orderhub.authorization.application.port.out.AuthorizationTransactionExecutor;
import io.github.piresrenan.orderhub.authorization.domain.model.AdministrativeGrant;
import io.github.piresrenan.orderhub.authorization.domain.model.AdministrativeScope;
import io.github.piresrenan.orderhub.authorization.domain.model.PermissionCode;

class AuditedAdministrativeGrantMutationServiceTest {

    @Test
    void appliedGrantProducesAppliedTransitionEvidenceAfterMutation() {

        var grants =
                mock(
                        AdministrativeGrantRepository.class);

        var audit =
                mock(
                        AdministrativeGrantAuditRepository.class);

        var grant =
                platformGrant();

        when(grants.grant(
                grant))
                .thenReturn(
                        AdministrativeGrantMutationResult.GRANTED);

        var auditId =
                UUID.randomUUID();

        var service =
                service(
                        grants,
                        audit,
                        auditId);

        var actor =
                UUID.randomUUID();

        var correlation =
                UUID.randomUUID();

        assertThat(
                service.grant(
                        actor,
                        grant,
                        correlation))
                .isEqualTo(
                        AdministrativeGrantMutationResult.GRANTED);

        var captor =
                ArgumentCaptor.forClass(
                        AdministrativeGrantAuditEvidence.class);

        var order =
                inOrder(
                        grants,
                        audit);

        order.verify(
                grants)
                .grant(
                        grant);

        order.verify(
                audit)
                .append(
                        captor.capture());

        var evidence =
                captor.getValue();

        assertThat(evidence.auditEventId())
                .isEqualTo(
                        auditId);

        assertThat(evidence.actorUserId())
                .isEqualTo(
                        actor);

        assertThat(evidence.targetUserId())
                .isEqualTo(
                        grant.userId());

        assertThat(evidence.action())
                .isEqualTo(
                        AdministrativeGrantAuditAction.GRANT_PERMISSION);

        assertThat(evidence.outcome())
                .isEqualTo(
                        AdministrativeGrantAuditOutcome.APPLIED);

        assertThat(evidence.beforeGranted())
                .isFalse();

        assertThat(evidence.afterGranted())
                .isTrue();

        assertThat(evidence.correlationId())
                .isEqualTo(
                        correlation);
    }

    @Test
    void idempotentGrantProducesNoChangeEvidence() {

        var grants =
                mock(
                        AdministrativeGrantRepository.class);

        var audit =
                mock(
                        AdministrativeGrantAuditRepository.class);

        var grant =
                platformGrant();

        when(grants.grant(
                grant))
                .thenReturn(
                        AdministrativeGrantMutationResult.ALREADY_GRANTED);

        var service =
                service(
                        grants,
                        audit,
                        UUID.randomUUID());

        service.grant(
                UUID.randomUUID(),
                grant,
                UUID.randomUUID());

        var captor =
                ArgumentCaptor.forClass(
                        AdministrativeGrantAuditEvidence.class);

        verify(audit)
                .append(
                        captor.capture());

        assertThat(captor.getValue().outcome())
                .isEqualTo(
                        AdministrativeGrantAuditOutcome.NO_CHANGE);

        assertThat(captor.getValue().beforeGranted())
                .isTrue();

        assertThat(captor.getValue().afterGranted())
                .isTrue();
    }

    @Test
    void appliedRevokeProducesAppliedTransitionEvidence() {

        var grants =
                mock(
                        AdministrativeGrantRepository.class);

        var audit =
                mock(
                        AdministrativeGrantAuditRepository.class);

        var grant =
                platformGrant();

        when(grants.revoke(
                grant))
                .thenReturn(
                        AdministrativeGrantMutationResult.REVOKED);

        var service =
                service(
                        grants,
                        audit,
                        UUID.randomUUID());

        service.revoke(
                UUID.randomUUID(),
                grant,
                UUID.randomUUID());

        var captor =
                ArgumentCaptor.forClass(
                        AdministrativeGrantAuditEvidence.class);

        verify(audit)
                .append(
                        captor.capture());

        assertThat(captor.getValue().action())
                .isEqualTo(
                        AdministrativeGrantAuditAction.REVOKE_PERMISSION);

        assertThat(captor.getValue().outcome())
                .isEqualTo(
                        AdministrativeGrantAuditOutcome.APPLIED);

        assertThat(captor.getValue().beforeGranted())
                .isTrue();

        assertThat(captor.getValue().afterGranted())
                .isFalse();
    }

    @Test
    void idempotentRevokeProducesNoChangeEvidence() {

        var grants =
                mock(
                        AdministrativeGrantRepository.class);

        var audit =
                mock(
                        AdministrativeGrantAuditRepository.class);

        var grant =
                platformGrant();

        when(grants.revoke(
                grant))
                .thenReturn(
                        AdministrativeGrantMutationResult.ALREADY_ABSENT);

        var service =
                service(
                        grants,
                        audit,
                        UUID.randomUUID());

        service.revoke(
                UUID.randomUUID(),
                grant,
                UUID.randomUUID());

        var captor =
                ArgumentCaptor.forClass(
                        AdministrativeGrantAuditEvidence.class);

        verify(audit)
                .append(
                        captor.capture());

        assertThat(captor.getValue().outcome())
                .isEqualTo(
                        AdministrativeGrantAuditOutcome.NO_CHANGE);

        assertThat(captor.getValue().beforeGranted())
                .isFalse();

        assertThat(captor.getValue().afterGranted())
                .isFalse();
    }

    @Test
    void requiresActorIdentity() {

        var service =
                service(
                        mock(AdministrativeGrantRepository.class),
                        mock(AdministrativeGrantAuditRepository.class),
                        UUID.randomUUID());

        assertThatThrownBy(() ->
                service.grant(
                        null,
                        platformGrant(),
                        UUID.randomUUID()))
                .isInstanceOf(
                        NullPointerException.class)
                .hasMessage(
                        "actorUserId");
    }

    @Test
    void requiresGrant() {

        var service =
                service(
                        mock(AdministrativeGrantRepository.class),
                        mock(AdministrativeGrantAuditRepository.class),
                        UUID.randomUUID());

        assertThatThrownBy(() ->
                service.grant(
                        UUID.randomUUID(),
                        null,
                        UUID.randomUUID()))
                .isInstanceOf(
                        NullPointerException.class)
                .hasMessage(
                        "grant");
    }

    @Test
    void requiresCorrelationIdentity() {

        var service =
                service(
                        mock(AdministrativeGrantRepository.class),
                        mock(AdministrativeGrantAuditRepository.class),
                        UUID.randomUUID());

        assertThatThrownBy(() ->
                service.grant(
                        UUID.randomUUID(),
                        platformGrant(),
                        null))
                .isInstanceOf(
                        NullPointerException.class)
                .hasMessage(
                        "correlationId");
    }

    @Test
    void auditFailurePropagatesAndIsNotConvertedToSuccessfulMutationResult() {

        var grants =
                mock(
                        AdministrativeGrantRepository.class);

        var audit =
                mock(
                        AdministrativeGrantAuditRepository.class);

        var grant =
                platformGrant();

        when(grants.grant(
                grant))
                .thenReturn(
                        AdministrativeGrantMutationResult.GRANTED);

        var failure =
                new AuthorizationPersistenceException(
                        new IllegalStateException(
                                "synthetic audit failure"));

        org.mockito.Mockito.doThrow(
                failure)
                .when(
                        audit)
                .append(
                        org.mockito.ArgumentMatchers.any());

        var service =
                service(
                        grants,
                        audit,
                        UUID.randomUUID());

        assertThatThrownBy(() ->
                service.grant(
                        UUID.randomUUID(),
                        grant,
                        UUID.randomUUID()))
                .isSameAs(
                        failure);
    }

    private static AuditedAdministrativeGrantMutationService service(
            AdministrativeGrantRepository grants,
            AdministrativeGrantAuditRepository audit,
            UUID auditEventId) {

        AuthorizationTransactionExecutor transactions =
                new AuthorizationTransactionExecutor() {

                    @Override
                    public <T> T execute(
                            java.util.function.Supplier<T> work) {

                        return work.get();
                    }
                };

        return new AuditedAdministrativeGrantMutationService(
                transactions,
                grants,
                audit,
                () -> auditEventId);
    }

    private static AdministrativeGrant platformGrant() {

        return new AdministrativeGrant(
                UUID.randomUUID(),
                AdministrativeScope.platform(),
                PermissionCode.PLATFORM_ORGANIZATIONS_VIEW);
    }
}
