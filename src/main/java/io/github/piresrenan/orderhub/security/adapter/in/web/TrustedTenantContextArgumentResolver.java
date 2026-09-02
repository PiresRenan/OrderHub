package io.github.piresrenan.orderhub.security.adapter.in.web;

import java.util.UUID;

import org.springframework.core.MethodParameter;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import io.github.piresrenan.orderhub.security.adapter.in.authentication.AuthenticatedUserAuthenticationToken;
import io.github.piresrenan.orderhub.security.application.model.AuthenticatedUserPrincipal;
import io.github.piresrenan.orderhub.security.application.model.TrustedActorContext;
import io.github.piresrenan.orderhub.security.application.model.TrustedTenantContext;
import io.github.piresrenan.orderhub.security.application.port.in.ResolveTrustedTenantContextQuery;
import io.github.piresrenan.orderhub.security.application.port.in.ResolveTrustedTenantContextUseCase;

/**
 * Adapts the authenticated internal User and untrusted HTTP Tenant selector into
 * trusted request context.
 *
 * <p>
 * The {@code X-Tenant-Id} header remains only a requested Tenant selector.
 * Neither TrustedTenantContext nor TrustedActorContext is produced until the
 * Security application boundary proves the exact internal User/Tenant
 * membership.
 * </p>
 */
public final class TrustedTenantContextArgumentResolver
        implements HandlerMethodArgumentResolver {

    private static final String TENANT_HEADER =
            "X-Tenant-Id";

    private static final String AUTHENTICATION_FAILURE_MESSAGE =
            "Bearer authentication required";

    private static final String TENANT_ACCESS_DENIED_MESSAGE =
            "Tenant access denied";

    private final ResolveTrustedTenantContextUseCase trustedTenants;

    public TrustedTenantContextArgumentResolver(
            ResolveTrustedTenantContextUseCase trustedTenants) {

        if (trustedTenants == null) {
            throw new IllegalArgumentException(
                    "Trusted tenant resolver is required");
        }

        this.trustedTenants =
                trustedTenants;
    }

    @Override
    public boolean supportsParameter(
            MethodParameter parameter) {

        var parameterType =
                parameter.getParameterType();

        return TrustedTenantContext.class.equals(
                parameterType)
                || TrustedActorContext.class.equals(
                        parameterType);
    }

    @Override
    public Object resolveArgument(
            MethodParameter parameter,
            ModelAndViewContainer mavContainer,
            NativeWebRequest webRequest,
            WebDataBinderFactory binderFactory)
            throws MissingRequestHeaderException {

        var authenticatedPrincipal =
                authenticatedPrincipal(
                        webRequest);

        var requestedTenantId =
                requestedTenantId(
                        parameter,
                        webRequest);

        var trustedTenant =
                trustedTenants
                        .resolve(
                                new ResolveTrustedTenantContextQuery(
                                        authenticatedPrincipal,
                                        requestedTenantId))
                        .orElseThrow(
                                () ->
                                        new AccessDeniedException(
                                                TENANT_ACCESS_DENIED_MESSAGE));

        /*
         * A misconfigured application boundary returning a different Tenant is
         * authorization-policy inconsistency and therefore fails closed.
         */
        if (!requestedTenantId.equals(
                trustedTenant.tenantId())) {

            throw new AccessDeniedException(
                    TENANT_ACCESS_DENIED_MESSAGE);
        }

        if (TrustedActorContext.class.equals(
                parameter.getParameterType())) {

            return new TrustedActorContext(
                    authenticatedPrincipal.userId(),
                    trustedTenant.tenantId());
        }

        return trustedTenant;
    }

    private AuthenticatedUserPrincipal authenticatedPrincipal(
            NativeWebRequest webRequest) {

        var requestPrincipal =
                webRequest.getUserPrincipal();

        if (!(requestPrincipal
                instanceof AuthenticatedUserAuthenticationToken authentication)
                || !authentication.isAuthenticated()) {

            throw new InsufficientAuthenticationException(
                    AUTHENTICATION_FAILURE_MESSAGE);
        }

        return authentication.getPrincipal();
    }

    private UUID requestedTenantId(
            MethodParameter parameter,
            NativeWebRequest webRequest)
            throws MissingRequestHeaderException {

        var rawTenantId =
                webRequest.getHeader(
                        TENANT_HEADER);

        if (rawTenantId == null) {
            throw new MissingRequestHeaderException(
                    TENANT_HEADER,
                    parameter);
        }

        try {
            return UUID.fromString(
                    rawTenantId);

        } catch (IllegalArgumentException exception) {

            throw new MethodArgumentTypeMismatchException(
                    null,
                    UUID.class,
                    TENANT_HEADER,
                    parameter,
                    null);
        }
    }
}
