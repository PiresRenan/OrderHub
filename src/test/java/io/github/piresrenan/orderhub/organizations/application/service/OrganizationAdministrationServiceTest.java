package io.github.piresrenan.orderhub.organizations.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.github.piresrenan.orderhub.authorization.application.port.in.administration.AuthorizeAdministrativeActionUseCase;
import io.github.piresrenan.orderhub.authorization.application.port.in.administration.MutateAdministrativeGrantUseCase;
import io.github.piresrenan.orderhub.authorization.domain.model.AuthorizationDecision;
import io.github.piresrenan.orderhub.organizations.application.port.in.administration.AdministrationAccessDeniedException;
import io.github.piresrenan.orderhub.organizations.application.port.in.administration.AdministrativeConflictException;
import io.github.piresrenan.orderhub.organizations.application.port.in.administration.OrganizationUnavailableException;
import io.github.piresrenan.orderhub.organizations.application.port.out.OrganizationAdministrativeAuditRepository;
import io.github.piresrenan.orderhub.organizations.application.port.out.OrganizationRepository;
import io.github.piresrenan.orderhub.organizations.application.port.out.OrganizationTenantPlacementRepository;
import io.github.piresrenan.orderhub.organizations.application.port.out.OrganizationTenantPlacementResult;
import io.github.piresrenan.orderhub.organizations.application.port.out.OrganizationTransactionExecutor;
import io.github.piresrenan.orderhub.organizations.domain.model.Organization;
import io.github.piresrenan.orderhub.organizations.domain.model.OrganizationStatus;
import io.github.piresrenan.orderhub.tenants.application.port.in.administration.AdministrativeTenant;
import io.github.piresrenan.orderhub.tenants.application.port.in.administration.FindTenantAdministrativeMetadataUseCase;
import io.github.piresrenan.orderhub.users.application.port.in.UserExistenceUseCase;

class OrganizationAdministrationServiceTest {

    private final AuthorizeAdministrativeActionUseCase authorization = mock(AuthorizeAdministrativeActionUseCase.class);
    private final MutateAdministrativeGrantUseCase grants = mock(MutateAdministrativeGrantUseCase.class);
    private final OrganizationRepository organizations = mock(OrganizationRepository.class);
    private final OrganizationTenantPlacementRepository placements = mock(OrganizationTenantPlacementRepository.class);
    private final FindTenantAdministrativeMetadataUseCase tenants = mock(FindTenantAdministrativeMetadataUseCase.class);
    private final UserExistenceUseCase users = mock(UserExistenceUseCase.class);
    private final OrganizationAdministrativeAuditRepository audit = mock(OrganizationAdministrativeAuditRepository.class);
    private OrganizationAdministrationService service;

    @BeforeEach
    void setUp() {
        service = new OrganizationAdministrationService(
                authorization, grants, organizations, placements, tenants, users,
                transactions(), audit, UUID::randomUUID);
    }

    @Test
    void deniedPlatformPlacementDoesNotProbeTenantOrTarget() {
        when(authorization.authorize(any())).thenReturn(AuthorizationDecision.DENY);

        assertThatThrownBy(() -> service.attachTenant(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()))
                .isInstanceOf(AdministrationAccessDeniedException.class);

        verifyNoInteractions(tenants, organizations, placements, audit);
    }

    @Test
    void deniedGrantDoesNotProbeOrganizationOrUser() {
        when(authorization.authorize(any())).thenReturn(AuthorizationDecision.DENY);

        assertThatThrownBy(() -> service.grant(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "ORGANIZATION_TENANTS_VIEW", UUID.randomUUID()))
                .isInstanceOf(AdministrationAccessDeniedException.class);

        verifyNoInteractions(organizations, users, grants);
    }

    @Test
    void deniedOrganizationQueryDoesNotRevealOrganizationState() {
        when(authorization.authorize(any())).thenReturn(AuthorizationDecision.DENY);

        assertThatThrownBy(() -> service.listTenants(UUID.randomUUID(), UUID.randomUUID()))
                .isInstanceOf(OrganizationUnavailableException.class);

        verifyNoInteractions(organizations, placements, tenants);
    }

    @Test
    void activeOrganizationQueryReturnsOnlyBoundedTenantMetadata() {
        var organizationId = UUID.randomUUID();
        var tenantId = UUID.randomUUID();
        when(authorization.authorize(any())).thenReturn(AuthorizationDecision.ALLOW);
        when(organizations.findById(organizationId))
                .thenReturn(Optional.of(Organization.create(organizationId, "Group")));
        when(placements.findTenantIdsByOrganizationId(organizationId))
                .thenReturn(List.of(tenantId));
        when(tenants.findById(tenantId))
                .thenReturn(Optional.of(new AdministrativeTenant(
                        tenantId, "Store", "SUSPENDED")));

        assertThat(service.listTenants(UUID.randomUUID(), organizationId))
                .containsExactly(new io.github.piresrenan.orderhub.organizations.application.port.in.administration.OrganizationTenantSummary(
                        tenantId, "Store", "SUSPENDED"));
    }

    @Test
    void attachWritesOwnerLocalAuditForAppliedMutation() {
        when(authorization.authorize(any())).thenReturn(AuthorizationDecision.ALLOW);
        when(tenants.findById(any())).thenReturn(Optional.of(
                new AdministrativeTenant(UUID.randomUUID(), "Store", "ACTIVE")));
        when(placements.attach(any(), any())).thenReturn(OrganizationTenantPlacementResult.ATTACHED);

        service.attachTenant(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());

        verify(audit).append(any());
    }

    @Test
    void rejectsMoveWhoseSourceAndDestinationAreIdenticalBeforeTargetProbes() {
        var organizationId = UUID.randomUUID();
        when(authorization.authorize(any())).thenReturn(AuthorizationDecision.ALLOW);

        assertThatThrownBy(() -> service.moveTenant(
                UUID.randomUUID(), organizationId, organizationId,
                UUID.randomUUID(), UUID.randomUUID()))
                .isInstanceOf(AdministrativeConflictException.class);

        verifyNoInteractions(tenants, placements, audit);
    }

    @Test
    void suspendedOrganizationIsIndistinguishableFromUnavailableAfterGrantCheck() {
        var organizationId = UUID.randomUUID();
        when(authorization.authorize(any())).thenReturn(AuthorizationDecision.ALLOW);
        when(organizations.findById(organizationId)).thenReturn(Optional.of(
                Organization.rehydrate(organizationId, "Group", OrganizationStatus.SUSPENDED)));

        assertThatThrownBy(() -> service.listTenants(UUID.randomUUID(), organizationId))
                .isInstanceOf(OrganizationUnavailableException.class);

        verifyNoInteractions(placements, tenants);
    }

    @Test
    void platformMayConfigureGrantWhileOrganizationIsSuspended() {
        var organizationId = UUID.randomUUID();
        var userId = UUID.randomUUID();
        when(authorization.authorize(any())).thenReturn(AuthorizationDecision.ALLOW);
        when(organizations.findById(organizationId)).thenReturn(Optional.of(
                Organization.rehydrate(organizationId, "Group", OrganizationStatus.SUSPENDED)));
        when(users.exists(userId)).thenReturn(true);

        service.grant(UUID.randomUUID(), organizationId, userId,
                "ORGANIZATION_TENANTS_VIEW", UUID.randomUUID());

        verify(grants).grant(any(), any(), any());
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
