package io.github.piresrenan.orderhub.tenants.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import org.mockito.ArgumentCaptor;

import java.util.UUID;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;

import io.github.piresrenan.orderhub.authorization.application.port.in.administration.AuthorizeAdministrativeActionUseCase;
import io.github.piresrenan.orderhub.authorization.domain.model.AuthorizationDecision;
import io.github.piresrenan.orderhub.tenants.application.port.out.TenantAdministrativeAuditRepository;
import io.github.piresrenan.orderhub.tenants.application.port.out.TenantLifecycleMutationResult;
import io.github.piresrenan.orderhub.tenants.application.port.out.TenantLifecycleRepository;
import io.github.piresrenan.orderhub.tenants.application.port.out.TenantRepository;
import io.github.piresrenan.orderhub.tenants.application.port.out.TenantTransactionExecutor;
import io.github.piresrenan.orderhub.tenants.application.port.in.administration.TenantAdministrationAccessDeniedException;
import io.github.piresrenan.orderhub.tenants.application.port.in.administration.TenantAdministrationNotFoundException;
import io.github.piresrenan.orderhub.tenants.application.model.TenantAdministrativeAuditEvidence;
import io.github.piresrenan.orderhub.tenants.application.model.TenantAdministrativeAuditOutcome;

class TenantAdministrationServiceTest {

    @Test
    void deniesBeforeTenantLookupOrMutation() {
        var authorization = mock(AuthorizeAdministrativeActionUseCase.class);
        var tenants = mock(TenantRepository.class);
        var lifecycle = mock(TenantLifecycleRepository.class);
        var audit = mock(TenantAdministrativeAuditRepository.class);
        var transactions = mock(TenantTransactionExecutor.class);
        when(authorization.authorize(any())).thenReturn(AuthorizationDecision.DENY);

        var service = service(authorization, tenants, lifecycle, transactions, audit);

        assertThatThrownBy(() -> service.suspend(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()))
                .isInstanceOf(TenantAdministrationAccessDeniedException.class);

        verifyNoInteractions(tenants, lifecycle, transactions, audit);
    }

    @Test
    void createsTenantAndAuditAtomically() {
        var authorization = mock(AuthorizeAdministrativeActionUseCase.class);
        var tenants = mock(TenantRepository.class);
        var audit = mock(TenantAdministrativeAuditRepository.class);
        when(authorization.authorize(any())).thenReturn(AuthorizationDecision.ALLOW);
        when(tenants.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var service = service(authorization, tenants,
                mock(TenantLifecycleRepository.class), transactions(), audit);
        var created = service.create(
                UUID.randomUUID(), "  Store  ", UUID.randomUUID());

        assertThat(created.name()).isEqualTo("Store");
        verify(tenants).save(any());
        verify(audit).append(any());
    }

    @Test
    void authorizedMissingLifecycleTargetIsNotFound() {
        var authorization = mock(AuthorizeAdministrativeActionUseCase.class);
        var tenants = mock(TenantRepository.class);
        var lifecycle = mock(TenantLifecycleRepository.class);
        when(authorization.authorize(any())).thenReturn(AuthorizationDecision.ALLOW);
        when(lifecycle.setStatus(any(), any()))
                .thenReturn(TenantLifecycleMutationResult.NOT_FOUND);

        var service = service(
                authorization, tenants, lifecycle, transactions(),
                mock(TenantAdministrativeAuditRepository.class));

        assertThatThrownBy(() -> service.recover(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()))
                .isInstanceOf(TenantAdministrationNotFoundException.class);
    }

    @Test
    void repeatedLifecycleRequestIsAuditedAsNoChange() {
        var authorization = mock(AuthorizeAdministrativeActionUseCase.class);
        var lifecycle = mock(TenantLifecycleRepository.class);
        var audit = mock(TenantAdministrativeAuditRepository.class);
        when(authorization.authorize(any())).thenReturn(AuthorizationDecision.ALLOW);
        when(lifecycle.setStatus(any(), any()))
                .thenReturn(TenantLifecycleMutationResult.ALREADY_DESIRED);
        var service = service(authorization, mock(TenantRepository.class),
                lifecycle, transactions(), audit);

        service.recover(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());

        var evidence = ArgumentCaptor.forClass(TenantAdministrativeAuditEvidence.class);
        verify(audit).append(evidence.capture());
        assertThat(evidence.getValue().outcome())
                .isEqualTo(TenantAdministrativeAuditOutcome.NO_CHANGE);
        assertThat(evidence.getValue().beforeStatus())
                .isEqualTo(evidence.getValue().afterStatus());
    }

    private static TenantAdministrationService service(
            AuthorizeAdministrativeActionUseCase authorization,
            TenantRepository tenants,
            TenantLifecycleRepository lifecycle,
            TenantTransactionExecutor transactions,
            TenantAdministrativeAuditRepository audit) {
        return new TenantAdministrationService(
                authorization, tenants, lifecycle, transactions, audit,
                UUID::randomUUID, UUID::randomUUID);
    }

    private static TenantTransactionExecutor transactions() {
        return new TenantTransactionExecutor() {
            @Override
            public <T> T execute(Supplier<T> work) {
                return work.get();
            }
        };
    }
}
