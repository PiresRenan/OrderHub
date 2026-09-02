package io.github.piresrenan.orderhub.security.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.ServletWebRequest;

import io.github.piresrenan.orderhub.security.adapter.in.authentication.AuthenticatedUserAuthenticationToken;
import io.github.piresrenan.orderhub.security.application.model.AuthenticatedUserPrincipal;
import io.github.piresrenan.orderhub.security.application.model.TrustedActorContext;
import io.github.piresrenan.orderhub.security.application.model.TrustedTenantContext;
import io.github.piresrenan.orderhub.security.application.port.in.ResolveTrustedTenantContextQuery;

class TrustedActorContextBoundaryTest {

    @Test
    void trustedActorRequiresInternalUserId() {

        assertThatThrownBy(() ->
                new TrustedActorContext(
                        null,
                        UUID.randomUUID()))
                .isInstanceOf(
                        IllegalArgumentException.class)
                .hasMessage(
                        "Trusted actor user id is required");
    }

    @Test
    void trustedActorRequiresTrustedTenantId() {

        assertThatThrownBy(() ->
                new TrustedActorContext(
                        UUID.randomUUID(),
                        null))
                .isInstanceOf(
                        IllegalArgumentException.class)
                .hasMessage(
                        "Trusted actor tenant id is required");
    }

    @Test
    void existingResolverAlsoSupportsTrustedActorContext() {

        var resolver =
                new TrustedTenantContextArgumentResolver(
                        query ->
                                Optional.empty());

        assertThat(
                resolver.supportsParameter(
                        trustedActorParameter()))
                .isTrue();
    }

    @Test
    void resolvesActorOnlyFromAuthenticatedInternalUserAndVerifiedTenant()
            throws Exception {

        var userId =
                UUID.randomUUID();

        var tenantId =
                UUID.randomUUID();

        var observedQuery =
                new AtomicReference<ResolveTrustedTenantContextQuery>();

        var resolver =
                new TrustedTenantContextArgumentResolver(
                        query -> {

                            observedQuery.set(
                                    query);

                            return Optional.of(
                                    new TrustedTenantContext(
                                            tenantId));
                        });

        var resolved =
                resolver.resolveArgument(
                        trustedActorParameter(),
                        null,
                        authenticatedRequest(
                                userId,
                                tenantId),
                        null);

        assertThat(resolved)
                .isEqualTo(
                        new TrustedActorContext(
                                userId,
                                tenantId));

        assertThat(observedQuery.get())
                .isEqualTo(
                        new ResolveTrustedTenantContextQuery(
                                new AuthenticatedUserPrincipal(
                                        userId),
                                tenantId));
    }

    private static ServletWebRequest authenticatedRequest(
            UUID userId,
            UUID tenantId) {

        var request =
                new MockHttpServletRequest();

        request.setUserPrincipal(
                new AuthenticatedUserAuthenticationToken(
                        new AuthenticatedUserPrincipal(
                                userId)));

        request.addHeader(
                "X-Tenant-Id",
                tenantId.toString());

        return new ServletWebRequest(
                request);
    }

    private static MethodParameter trustedActorParameter() {

        try {
            var method =
                    TestEndpoint.class
                            .getDeclaredMethod(
                                    "authorizedEndpoint",
                                    TrustedActorContext.class);

            return new MethodParameter(
                    method,
                    0);

        } catch (NoSuchMethodException exception) {

            throw new AssertionError(
                    exception);
        }
    }

    private static final class TestEndpoint {

        @SuppressWarnings("unused")
        void authorizedEndpoint(
                TrustedActorContext context) {
        }
    }
}
