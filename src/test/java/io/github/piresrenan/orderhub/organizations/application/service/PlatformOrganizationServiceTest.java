package io.github.piresrenan.orderhub.organizations.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;

import io.github.piresrenan.orderhub.authorization.application.port.in.administration.AuthorizeAdministrativeActionUseCase;
import io.github.piresrenan.orderhub.authorization.domain.model.AuthorizationDecision;
import io.github.piresrenan.orderhub.organizations.application.port.out.OrganizationAdministrativeAuditRepository;
import io.github.piresrenan.orderhub.organizations.application.port.out.OrganizationLifecycleMutationResult;
import io.github.piresrenan.orderhub.organizations.application.port.out.OrganizationLifecycleRepository;
import io.github.piresrenan.orderhub.organizations.application.port.out.OrganizationRepository;
import io.github.piresrenan.orderhub.organizations.application.port.out.OrganizationTransactionExecutor;
import io.github.piresrenan.orderhub.organizations.application.port.in.administration.PlatformAdministrationAccessDeniedException;
import io.github.piresrenan.orderhub.organizations.application.port.in.administration.OrganizationNotFoundException;
import io.github.piresrenan.orderhub.organizations.domain.model.Organization;
import io.github.piresrenan.orderhub.organizations.application.model.OrganizationAdministrativeAuditEvidence;
import io.github.piresrenan.orderhub.organizations.application.model.OrganizationAdministrativeAuditOutcome;

class PlatformOrganizationServiceTest {

    @Test
    void deniesBeforeOrganizationPersistenceIsTouched() {

        var authorization = mock(AuthorizeAdministrativeActionUseCase.class);
        var organizations = mock(OrganizationRepository.class);
        var lifecycle = mock(OrganizationLifecycleRepository.class);
        var transactions = mock(OrganizationTransactionExecutor.class);
        var audit = mock(OrganizationAdministrativeAuditRepository.class);

        when(authorization.authorize(any()))
                .thenReturn(AuthorizationDecision.DENY);

        var service = new PlatformOrganizationService(
                authorization,
                organizations,
                lifecycle,
                transactions,
                audit,
                () -> UUID.randomUUID(),
                () -> UUID.randomUUID());

        assertThatThrownBy(() -> service.suspend(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID()))
                .isInstanceOf(PlatformAdministrationAccessDeniedException.class);

        verifyNoInteractions(organizations, lifecycle, transactions, audit);
    }

    @Test
    void createsOrganizationAndAuditInsideOneTransaction() {

        var authorization = mock(AuthorizeAdministrativeActionUseCase.class);
        var organizations = mock(OrganizationRepository.class);
        var lifecycle = mock(OrganizationLifecycleRepository.class);
        var audit = mock(OrganizationAdministrativeAuditRepository.class);

        when(authorization.authorize(any()))
                .thenReturn(AuthorizationDecision.ALLOW);
        when(organizations.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        var transactions = transactions();
        var organizationId = UUID.randomUUID();

        var service = new PlatformOrganizationService(
                authorization,
                organizations,
                lifecycle,
                transactions,
                audit,
                () -> organizationId,
                () -> UUID.randomUUID());

        var created = service.create(
                UUID.randomUUID(),
                "  North  ",
                UUID.randomUUID());

        assertThat(created.id()).isEqualTo(organizationId);
        assertThat(created.name()).isEqualTo("North");
        verify(organizations).save(any());
        verify(audit).append(any());
    }

    @Test
    void reportsAuthorizedMissingLifecycleTarget() {

        var authorization = mock(AuthorizeAdministrativeActionUseCase.class);
        var organizations = mock(OrganizationRepository.class);
        var lifecycle = mock(OrganizationLifecycleRepository.class);
        var audit = mock(OrganizationAdministrativeAuditRepository.class);

        when(authorization.authorize(any()))
                .thenReturn(AuthorizationDecision.ALLOW);
        when(lifecycle.setStatus(any(), any()))
                .thenReturn(OrganizationLifecycleMutationResult.NOT_FOUND);

        var transactions = transactions();

        var service = new PlatformOrganizationService(
                authorization,
                organizations,
                lifecycle,
                transactions,
                audit,
                () -> UUID.randomUUID(),
                () -> UUID.randomUUID());

        assertThatThrownBy(() -> service.recover(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID()))
                .isInstanceOf(OrganizationNotFoundException.class);

        verifyNoInteractions(audit);
    }

    @Test
    void listsOnlyAfterViewAuthorization() {

        var authorization = mock(AuthorizeAdministrativeActionUseCase.class);
        var organizations = mock(OrganizationRepository.class);

        when(authorization.authorize(any()))
                .thenReturn(AuthorizationDecision.ALLOW);
        when(organizations.findAll())
                .thenReturn(List.of());

        var service = new PlatformOrganizationService(
                authorization,
                organizations,
                mock(OrganizationLifecycleRepository.class),
                transactions(),
                mock(OrganizationAdministrativeAuditRepository.class),
                () -> UUID.randomUUID(),
                () -> UUID.randomUUID());

        assertThat(service.list(UUID.randomUUID())).isEmpty();
        verify(organizations).findAll();
    }

    @Test
    void repeatedLifecycleRequestIsAuditedAsNoChange() {
        var authorization = mock(AuthorizeAdministrativeActionUseCase.class);
        var lifecycle = mock(OrganizationLifecycleRepository.class);
        var audit = mock(OrganizationAdministrativeAuditRepository.class);
        when(authorization.authorize(any())).thenReturn(AuthorizationDecision.ALLOW);
        when(lifecycle.setStatus(any(), any()))
                .thenReturn(OrganizationLifecycleMutationResult.ALREADY_DESIRED);
        var service = new PlatformOrganizationService(
                authorization, mock(OrganizationRepository.class), lifecycle,
                transactions(), audit, UUID::randomUUID, UUID::randomUUID);

        service.suspend(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());

        var evidence = ArgumentCaptor.forClass(OrganizationAdministrativeAuditEvidence.class);
        verify(audit).append(evidence.capture());
        assertThat(evidence.getValue().outcome())
                .isEqualTo(OrganizationAdministrativeAuditOutcome.NO_CHANGE);
        assertThat(evidence.getValue().beforeOrganizationStatus())
                .isEqualTo(evidence.getValue().afterOrganizationStatus());
    }

    private static OrganizationTransactionExecutor transactions() {
        return new OrganizationTransactionExecutor() {
            @Override
            public <T> T execute(Supplier<T> work) {
                return work.get();
            }
        };
    }
}
