package io.github.piresrenan.orderhub.authorization.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.Test;

class AdministrativeAuthorizationModelTest {

    @Test
    void platformScopeCarriesNoScopedResourceIdentity() {

        var scope =
                AdministrativeScope.platform();

        assertThat(scope.type())
                .isEqualTo(
                        AdministrativeScopeType.PLATFORM);

        assertThat(scope.scopeId())
                .isNull();
    }

    @Test
    void organizationAndTenantScopesRequireScopedResourceIdentity() {

        assertThatThrownBy(() ->
                AdministrativeScope.organization(
                        null))
                .isInstanceOf(
                        IllegalArgumentException.class)
                .hasMessage(
                        "Scoped administrative scope requires a resource id");

        assertThatThrownBy(() ->
                AdministrativeScope.tenant(
                        null))
                .isInstanceOf(
                        IllegalArgumentException.class)
                .hasMessage(
                        "Scoped administrative scope requires a resource id");
    }

    @Test
    void platformScopeRejectsScopedResourceIdentity() {

        assertThatThrownBy(() ->
                new AdministrativeScope(
                        AdministrativeScopeType.PLATFORM,
                        UUID.randomUUID()))
                .isInstanceOf(
                        IllegalArgumentException.class)
                .hasMessage(
                        "Platform administrative scope cannot carry a resource id");
    }

    @Test
    void platformPermissionsAreClassifiedForPlatformOnly() {

        assertThat(
                PermissionCode.PLATFORM_ORGANIZATIONS_VIEW
                        .isAdministrative())
                .isTrue();

        assertThat(
                PermissionCode.PLATFORM_ORGANIZATIONS_VIEW
                        .supportsAdministrativeScope(
                                AdministrativeScopeType.PLATFORM))
                .isTrue();

        assertThat(
                PermissionCode.PLATFORM_ORGANIZATIONS_VIEW
                        .supportsAdministrativeScope(
                                AdministrativeScopeType.ORGANIZATION))
                .isFalse();
    }

    @Test
    void organizationPermissionIsClassifiedForOrganizationOnly() {

        assertThat(
                PermissionCode.ORGANIZATION_TENANTS_VIEW
                        .isAdministrative())
                .isTrue();

        assertThat(
                PermissionCode.ORGANIZATION_TENANTS_VIEW
                        .supportsAdministrativeScope(
                                AdministrativeScopeType.ORGANIZATION))
                .isTrue();

        assertThat(
                PermissionCode.ORGANIZATION_TENANTS_VIEW
                        .supportsAdministrativeScope(
                                AdministrativeScopeType.PLATFORM))
                .isFalse();
    }

    @Test
    void tenantBusinessPermissionRemainsPersonaBoundAndNotAdministrative() {

        assertThat(
                PermissionCode.ORDERS_VIEW
                        .supports(
                                AuthorizationPersona.STAFF))
                .isTrue();

        assertThat(
                PermissionCode.ORDERS_VIEW
                        .isAdministrative())
                .isFalse();

        assertThat(
                PermissionCode.ORDERS_VIEW
                        .supportsAdministrativeScope(
                                AdministrativeScopeType.TENANT))
                .isFalse();
    }

    @Test
    void administrativePermissionsNeverMasqueradeAsTenantPersonas() {

        assertThat(
                PermissionCode.PLATFORM_TENANTS_MANAGE
                        .supports(
                                AuthorizationPersona.STAFF))
                .isFalse();

        assertThat(
                PermissionCode.PLATFORM_TENANTS_MANAGE
                        .supports(
                                AuthorizationPersona.CUSTOMER))
                .isFalse();
    }

    @Test
    void compatibleAdministrativeGrantIsAccepted() {

        var userId =
                UUID.randomUUID();

        var grant =
                new AdministrativeGrant(
                        userId,
                        AdministrativeScope.platform(),
                        PermissionCode.PLATFORM_ORGANIZATIONS_MANAGE);

        assertThat(grant.userId())
                .isEqualTo(
                        userId);
    }

    @Test
    void platformPermissionCannotBecomeOrganizationGrant() {

        assertThatThrownBy(() ->
                new AdministrativeGrant(
                        UUID.randomUUID(),
                        AdministrativeScope.organization(
                                UUID.randomUUID()),
                        PermissionCode.PLATFORM_ORGANIZATIONS_MANAGE))
                .isInstanceOf(
                        IllegalArgumentException.class)
                .hasMessage(
                        "Permission is incompatible with administrative scope");
    }

    @Test
    void organizationPermissionCannotBecomePlatformGrant() {

        assertThatThrownBy(() ->
                new AdministrativeGrant(
                        UUID.randomUUID(),
                        AdministrativeScope.platform(),
                        PermissionCode.ORGANIZATION_TENANTS_VIEW))
                .isInstanceOf(
                        IllegalArgumentException.class)
                .hasMessage(
                        "Permission is incompatible with administrative scope");
    }

    @Test
    void tenantBusinessPermissionCannotBecomeAdministrativeGrant() {

        assertThatThrownBy(() ->
                new AdministrativeGrant(
                        UUID.randomUUID(),
                        AdministrativeScope.tenant(
                                UUID.randomUUID()),
                        PermissionCode.ORDERS_VIEW))
                .isInstanceOf(
                        IllegalArgumentException.class)
                .hasMessage(
                        "Permission is incompatible with administrative scope");
    }

    @Test
    void administrativeGrantRequiresInternalUserIdentity() {

        assertThatThrownBy(() ->
                new AdministrativeGrant(
                        null,
                        AdministrativeScope.platform(),
                        PermissionCode.PLATFORM_ORGANIZATIONS_VIEW))
                .isInstanceOf(
                        IllegalArgumentException.class)
                .hasMessage(
                        "Administrative grant user id is required");
    }
}
