package io.github.piresrenan.orderhub.security.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import org.springframework.core.MethodParameter;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import io.github.piresrenan.orderhub.security.adapter.in.authentication.AuthenticatedUserAuthenticationToken;
import io.github.piresrenan.orderhub.security.application.model.AuthenticatedUserPrincipal;
import io.github.piresrenan.orderhub.security.application.model.TrustedTenantContext;
import io.github.piresrenan.orderhub.security.application.port.in.ResolveTrustedTenantContextQuery;
import io.github.piresrenan.orderhub.security.application.port.in.ResolveTrustedTenantContextUseCase;

class TrustedTenantContextArgumentResolverTest {

    private static final String TENANT_HEADER =
            "X-Tenant-Id";

    @Test
    void supportsOnlyTrustedTenantContextParameters() {
        // Why: the resolver must never interfere with unrelated MVC arguments.
        // Covers: exact resolver type selection.
        // Prevents: Security accidentally claiming ordinary controller parameters.

        var resolver =
                new TrustedTenantContextArgumentResolver(
                        query -> Optional.empty());

        assertThat(
                resolver.supportsParameter(
                        trustedTenantParameter()))
                .isTrue();

        assertThat(
                resolver.supportsParameter(
                        unrelatedParameter()))
                .isFalse();
    }

    @Test
    void resolvesContextFromInternalPrincipalAndRequestedTenant() throws Exception {
        // Why: X-Tenant-Id is only a selector and becomes trusted exclusively
        // after it is paired with the authenticated internal User.
        // Covers: request principal + header -> Security application query ->
        // TrustedTenantContext.
        // Prevents: raw tenant input bypassing membership verification.

        var userId =
                UUID.randomUUID();

        var tenantId =
                UUID.randomUUID();

        var observedQuery =
                new AtomicReference<ResolveTrustedTenantContextQuery>();

        ResolveTrustedTenantContextUseCase trustedTenants =
                query -> {
                    observedQuery.set(
                            query);

                    return Optional.of(
                            new TrustedTenantContext(
                                    tenantId));
                };

        var resolver =
                new TrustedTenantContextArgumentResolver(
                        trustedTenants);

        var resolved =
                resolver.resolveArgument(
                        trustedTenantParameter(),
                        null,
                        authenticatedRequest(
                                userId,
                                tenantId.toString()),
                        null);

        assertThat(resolved)
                .isEqualTo(
                        new TrustedTenantContext(
                                tenantId));

        assertThat(observedQuery)
                .hasValue(
                        new ResolveTrustedTenantContextQuery(
                                new AuthenticatedUserPrincipal(
                                        userId),
                                tenantId));
    }

    @Test
    void rejectsMissingTenantSelectorAsRequestBindingFailure() {
        // Why: a missing selector is malformed request metadata, not an
        // authentication or authorization decision.
        // Covers: absent X-Tenant-Id before membership lookup.
        // Prevents: unscoped Tenant access and semantic drift from the existing
        // Orders HTTP contract.

        var membershipLookupAttempted =
                new AtomicBoolean();

        ResolveTrustedTenantContextUseCase trustedTenants =
                query -> {
                    membershipLookupAttempted.set(
                            true);

                    return Optional.empty();
                };

        var resolver =
                new TrustedTenantContextArgumentResolver(
                        trustedTenants);

        var request =
                authenticatedRequest(
                        UUID.randomUUID(),
                        null);

        assertThatThrownBy(
                () -> resolver.resolveArgument(
                        trustedTenantParameter(),
                        null,
                        request,
                        null))
                .isInstanceOf(
                        MissingRequestHeaderException.class)
                .satisfies(exception ->
                        assertThat(
                                ((MissingRequestHeaderException) exception)
                                        .getHeaderName())
                                .isEqualTo(
                                        TENANT_HEADER));

        assertThat(membershipLookupAttempted)
                .isFalse();
    }

    @Test
    void rejectsMalformedTenantSelectorAsTypeMismatch() {
        // Why: an invalid UUID is syntactically malformed input and must not
        // become an authorization lookup.
        // Covers: X-Tenant-Id UUID parsing before membership resolution.
        // Prevents: malformed identifiers reaching Users or being misreported
        // as authorization failures.

        var membershipLookupAttempted =
                new AtomicBoolean();

        ResolveTrustedTenantContextUseCase trustedTenants =
                query -> {
                    membershipLookupAttempted.set(
                            true);

                    return Optional.empty();
                };

        var resolver =
                new TrustedTenantContextArgumentResolver(
                        trustedTenants);

        var request =
                authenticatedRequest(
                        UUID.randomUUID(),
                        "not-a-uuid");

        assertThatThrownBy(
                () -> resolver.resolveArgument(
                        trustedTenantParameter(),
                        null,
                        request,
                        null))
                .isInstanceOf(
                        MethodArgumentTypeMismatchException.class)
                .satisfies(exception -> {
                    var mismatch =
                            (MethodArgumentTypeMismatchException) exception;

                    assertThat(mismatch.getName())
                            .isEqualTo(
                                    TENANT_HEADER);

                    assertThat(mismatch.getRequiredType())
                            .isEqualTo(
                                    UUID.class);
                });

        assertThat(membershipLookupAttempted)
                .isFalse();
    }

    @Test
    void deniesAuthenticatedUserWithoutMembershipWithoutLeakingIdentifiers() {
        // Why: authentication alone must never authorize an arbitrary requested
        // Tenant.
        // Covers: successful principal/header parsing with absent membership.
        // Prevents: forged X-Tenant-Id crossing Tenant boundaries or exposing
        // existence information.

        var userId =
                UUID.randomUUID();

        var tenantId =
                UUID.randomUUID();

        var resolver =
                new TrustedTenantContextArgumentResolver(
                        query -> Optional.empty());

        assertThatThrownBy(
                () -> resolver.resolveArgument(
                        trustedTenantParameter(),
                        null,
                        authenticatedRequest(
                                userId,
                                tenantId.toString()),
                        null))
                .isInstanceOf(
                        AccessDeniedException.class)
                .satisfies(exception -> {
                    var message =
                            exception.getMessage();

                    assertThat(message)
                            .isNotBlank()
                            .doesNotContain(
                                    userId.toString(),
                                    tenantId.toString());
                });
    }

    @Test
    void failsClosedWhenInternalAuthenticatedPrincipalIsUnavailable() {
        // Why: TrustedTenantContext may only originate after successful
        // authentication into OrderHub's internal principal.
        // Covers: defensive failure when the MVC boundary receives no internal
        // authenticated identity.
        // Prevents: Tenant derivation from headers when authentication context is
        // absent or incorrectly composed.

        var resolver =
                new TrustedTenantContextArgumentResolver(
                        query ->
                                Optional.of(
                                        new TrustedTenantContext(
                                                UUID.randomUUID())));

        var request =
                new ServletWebRequest(
                        new MockHttpServletRequest());

        assertThatThrownBy(
                () -> resolver.resolveArgument(
                        trustedTenantParameter(),
                        null,
                        request,
                        null))
                .isInstanceOf(
                        InsufficientAuthenticationException.class);
    }

    @Test
    void rejectsMissingTrustedTenantApplicationBoundary() {
        // Why: the web adapter cannot safely operate without the application
        // boundary that proves membership.
        // Covers: constructor fail-fast behavior.
        // Prevents: partially configured Security infrastructure failing open.

        assertThatThrownBy(
                () -> new TrustedTenantContextArgumentResolver(
                        null))
                .isInstanceOf(
                        IllegalArgumentException.class)
                .hasMessage(
                        "Trusted tenant resolver is required");
    }

    private static ServletWebRequest authenticatedRequest(
            UUID userId,
            String requestedTenantId) {

        var request =
                new MockHttpServletRequest();

        request.setUserPrincipal(
                new AuthenticatedUserAuthenticationToken(
                        new AuthenticatedUserPrincipal(
                                userId)));

        if (requestedTenantId != null) {
            request.addHeader(
                    TENANT_HEADER,
                    requestedTenantId);
        }

        return new ServletWebRequest(
                request);
    }

    private static MethodParameter trustedTenantParameter() {
        return methodParameter(
                "trustedTenantEndpoint",
                TrustedTenantContext.class);
    }

    private static MethodParameter unrelatedParameter() {
        return methodParameter(
                "unrelatedEndpoint",
                String.class);
    }

    private static MethodParameter methodParameter(
            String methodName,
            Class<?> parameterType) {

        try {
            var method =
                    TestEndpoint.class
                            .getDeclaredMethod(
                                    methodName,
                                    parameterType);

            return new MethodParameter(
                    method,
                    0);
        } catch (NoSuchMethodException exception) {
            throw new AssertionError(
                    "Test method fixture is invalid",
                    exception);
        }
    }

    private static final class TestEndpoint {

        @SuppressWarnings("unused")
        void trustedTenantEndpoint(
                TrustedTenantContext context) {
        }

        @SuppressWarnings("unused")
        void unrelatedEndpoint(
                String value) {
        }
    }
}
