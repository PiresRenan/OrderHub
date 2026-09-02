package io.github.piresrenan.orderhub.authorization;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

import io.github.piresrenan.orderhub.OrderHubApplication;

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
}
