package io.github.piresrenan.orderhub.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;

import io.github.piresrenan.orderhub.security.adapter.in.web.TrustedTenantContextArgumentResolver;
import io.github.piresrenan.orderhub.support.PostgreSqlTestConfiguration;
import io.github.piresrenan.orderhub.users.application.port.in.FindTenantMembershipUseCase;
import io.github.piresrenan.orderhub.users.application.port.in.ResolveExternalIdentityUseCase;

@SpringBootTest(properties = {
        "orderhub.security.jwt.issuer=https://issuer.example.test",
        "orderhub.security.jwt.audience=orderhub-api",
        "orderhub.security.jwt.jwk-set-uri=http://127.0.0.1:1/test-only-jwks"
})
@AutoConfigureMockMvc
@Import(PostgreSqlTestConfiguration.class)
class SecurityTenantContextMvcRegistrationTest {

    @Autowired
    private RequestMappingHandlerAdapter handlerAdapter;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @MockitoBean
    private ResolveExternalIdentityUseCase externalIdentities;

    @MockitoBean
    private FindTenantMembershipUseCase memberships;

    @Test
    void registersTrustedTenantContextResolverWithSpringMvc() {
        // Why: implementing a HandlerMethodArgumentResolver does not by itself
        // make Spring MVC invoke it for controller parameters.
        // Covers: actual application RequestMappingHandlerAdapter registration.
        // Prevents: controllers declaring TrustedTenantContext while MVC silently
        // falls back to unrelated built-in argument resolution.

        var configuredResolvers =
                handlerAdapter.getArgumentResolvers();

        assertThat(configuredResolvers)
                .isNotNull();

        assertThat(configuredResolvers)
                .filteredOn(
                        TrustedTenantContextArgumentResolver.class::isInstance)
                .hasSize(
                        1);
    }
}
