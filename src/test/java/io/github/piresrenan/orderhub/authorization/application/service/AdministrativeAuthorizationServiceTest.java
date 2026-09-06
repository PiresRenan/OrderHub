package io.github.piresrenan.orderhub.authorization.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.github.piresrenan.orderhub.authorization.application.port.out.AdministrativeGrantRepository;
import io.github.piresrenan.orderhub.authorization.application.port.out.AuthorizationPersistenceException;
import io.github.piresrenan.orderhub.authorization.domain.model.AdministrativeGrant;
import io.github.piresrenan.orderhub.authorization.domain.model.AdministrativeScope;
import io.github.piresrenan.orderhub.authorization.domain.model.AuthorizationDecision;
import io.github.piresrenan.orderhub.authorization.domain.model.PermissionCode;

class AdministrativeAuthorizationServiceTest {

    @Test
    void exactPersistedGrantAllowsAdministrativeAction() {

        var repository =
                mock(
                        AdministrativeGrantRepository.class);

        var grant =
                platformGrant(
                        UUID.randomUUID());

        when(repository.exists(
                grant))
                .thenReturn(
                        true);

        var service =
                new AdministrativeAuthorizationService(
                        repository);

        assertThat(
                service.authorize(
                        grant))
                .isEqualTo(
                        AuthorizationDecision.ALLOW);
    }

    @Test
    void absentGrantDeniesAdministrativeAction() {

        var repository =
                mock(
                        AdministrativeGrantRepository.class);

        var service =
                new AdministrativeAuthorizationService(
                        repository);

        assertThat(
                service.authorize(
                        platformGrant(
                                UUID.randomUUID())))
                .isEqualTo(
                        AuthorizationDecision.DENY);
    }

    @Test
    void organizationGrantIsExactToOrganizationIdentity() {

        var repository =
                mock(
                        AdministrativeGrantRepository.class);

        var userId =
                UUID.randomUUID();

        var grantA =
                new AdministrativeGrant(
                        userId,
                        AdministrativeScope.organization(
                                UUID.randomUUID()),
                        PermissionCode.ORGANIZATION_TENANTS_VIEW);

        var grantB =
                new AdministrativeGrant(
                        userId,
                        AdministrativeScope.organization(
                                UUID.randomUUID()),
                        PermissionCode.ORGANIZATION_TENANTS_VIEW);

        when(repository.exists(
                grantA))
                .thenReturn(
                        true);

        var service =
                new AdministrativeAuthorizationService(
                        repository);

        assertThat(
                service.authorize(
                        grantA))
                .isEqualTo(
                        AuthorizationDecision.ALLOW);

        assertThat(
                service.authorize(
                        grantB))
                .isEqualTo(
                        AuthorizationDecision.DENY);
    }

    @Test
    void platformGrantDoesNotImplyOrganizationAuthority() {

        var repository =
                mock(
                        AdministrativeGrantRepository.class);

        var userId =
                UUID.randomUUID();

        var platformGrant =
                new AdministrativeGrant(
                        userId,
                        AdministrativeScope.platform(),
                        PermissionCode.PLATFORM_ORGANIZATIONS_VIEW);

        var organizationGrant =
                new AdministrativeGrant(
                        userId,
                        AdministrativeScope.organization(
                                UUID.randomUUID()),
                        PermissionCode.ORGANIZATION_TENANTS_VIEW);

        when(repository.exists(
                platformGrant))
                .thenReturn(
                        true);

        var service =
                new AdministrativeAuthorizationService(
                        repository);

        assertThat(
                service.authorize(
                        platformGrant))
                .isEqualTo(
                        AuthorizationDecision.ALLOW);

        assertThat(
                service.authorize(
                        organizationGrant))
                .isEqualTo(
                        AuthorizationDecision.DENY);
    }

    @Test
    void persistenceFailurePropagatesInsteadOfBecomingFalsePolicyDenial() {

        var repository =
                mock(
                        AdministrativeGrantRepository.class);

        var grant =
                platformGrant(
                        UUID.randomUUID());

        var failure =
                new AuthorizationPersistenceException(
                        new IllegalStateException(
                                "synthetic persistence failure"));

        when(repository.exists(
                grant))
                .thenThrow(
                        failure);

        var service =
                new AdministrativeAuthorizationService(
                        repository);

        assertThatThrownBy(() ->
                service.authorize(
                        grant))
                .isSameAs(
                        failure);
    }

    private static AdministrativeGrant platformGrant(
            UUID userId) {

        return new AdministrativeGrant(
                userId,
                AdministrativeScope.platform(),
                PermissionCode.PLATFORM_ORGANIZATIONS_VIEW);
    }
}
