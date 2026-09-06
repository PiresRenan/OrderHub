package io.github.piresrenan.orderhub.authorization;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

import io.github.piresrenan.orderhub.OrderHubApplication;
import io.github.piresrenan.orderhub.authorization.application.port.in.administration.AuthorizeAdministrativeActionUseCase;
import io.github.piresrenan.orderhub.authorization.domain.model.AdministrativeGrant;
import io.github.piresrenan.orderhub.authorization.domain.model.AdministrativeScope;
import io.github.piresrenan.orderhub.authorization.domain.model.AdministrativeScopeType;
import io.github.piresrenan.orderhub.authorization.domain.model.AuthorizationDecision;
import io.github.piresrenan.orderhub.authorization.domain.model.PermissionCode;

class AuthorizationModuleContractTest {

    @Test
    void authorizationIsDetectedAsAnIndependentApplicationModule() {

        var modules =
                ApplicationModules.of(
                        OrderHubApplication.class);

        var authorization =
                modules.getModuleByName(
                        "authorization");

        var users =
                modules.getModuleByName(
                        "users");

        var security =
                modules.getModuleByName(
                        "security");

        assertThat(authorization)
                .isPresent();

        assertThat(users)
                .isPresent();

        assertThat(security)
                .isPresent();

        assertThat(
                authorization.orElseThrow())
                .isNotSameAs(
                        users.orElseThrow())
                .isNotSameAs(
                        security.orElseThrow());
    }

    @Test
    void administrationNamedInterfaceExposesItsCompleteFrameworkNeutralContract() {

        var authorization =
                ApplicationModules.of(
                                OrderHubApplication.class)
                        .getModuleByName(
                                "authorization")
                        .orElseThrow();

        var administration =
                authorization.getNamedInterfaces()
                        .getByName(
                                "administration")
                        .orElseThrow();

        assertThat(
                administration.contains(
                        AuthorizeAdministrativeActionUseCase.class))
                .isTrue();

        assertThat(
                administration.contains(
                        AuthorizationDecision.class))
                .isTrue();

        assertThat(
                administration.contains(
                        AdministrativeGrant.class))
                .isTrue();

        assertThat(
                administration.contains(
                        AdministrativeScope.class))
                .isTrue();

        assertThat(
                administration.contains(
                        AdministrativeScopeType.class))
                .isTrue();

        assertThat(
                administration.contains(
                        PermissionCode.class))
                .isTrue();
    }
}
