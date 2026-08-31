package io.github.piresrenan.orderhub.security.adapter.in.web;

import java.util.UUID;

import org.springframework.core.MethodParameter;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.bind.support.WebDataBinderFactory;

import io.github.piresrenan.orderhub.security.adapter.in.authentication.AuthenticatedUserAuthenticationToken;
import io.github.piresrenan.orderhub.security.application.model.TrustedTenantContext;
import io.github.piresrenan.orderhub.security.application.port.in.ResolveTrustedTenantContextQuery;
import io.github.piresrenan.orderhub.security.application.port.in.ResolveTrustedTenantContextUseCase;

/**
 * Adapts the authenticated internal User and untrusted HTTP Tenant selector into
 * a trusted Tenant context for controller method parameters.
 *
 * <p>The {@code X-Tenant-Id} header is treated only as a requested Tenant
 * selector. This resolver does not trust the caller-provided value until the
 * Security application boundary confirms membership for the authenticated
 * internal User.
 *
 * <p>Authentication state remains owned by Spring Security while Tenant access
 * policy remains owned by the framework-neutral Security application layer.
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

    /**
     * Creates the MVC adapter with the application boundary responsible for
     * proving Tenant membership.
     *
     * @param trustedTenants trusted Tenant-context resolution boundary
     */
    public TrustedTenantContextArgumentResolver(
            ResolveTrustedTenantContextUseCase trustedTenants) {

        if (trustedTenants == null) {
            throw new IllegalArgumentException(
                    "Trusted tenant resolver is required");
        }

        this.trustedTenants = trustedTenants;
    }

    /**
     * Restricts this resolver to the explicit trusted Tenant context contract.
     *
     * @param parameter controller method parameter under inspection
     * @return {@code true} only for {@link TrustedTenantContext} parameters
     */
    @Override
    public boolean supportsParameter(
            MethodParameter parameter) {

        return TrustedTenantContext.class.equals(
                parameter.getParameterType());
    }

    /**
     * Resolves one trusted Tenant context from the authenticated internal User
     * and the untrusted Tenant selector supplied by the current request.
     *
     * <p>Missing or malformed selectors are request-validation failures and are
     * rejected before membership resolution. An absent membership is reported as
     * a stable authorization denial without exposing User or Tenant identifiers.
     *
     * @param parameter controller parameter being resolved
     * @param mavContainer MVC model container, unused by this resolver
     * @param webRequest current request abstraction
     * @param binderFactory MVC binder factory, unused by this resolver
     * @return trusted Tenant context after successful membership verification
     */
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

        return trustedTenants
                .resolve(
                        new ResolveTrustedTenantContextQuery(
                                authenticatedPrincipal,
                                requestedTenantId))
                .orElseThrow(
                        () ->
                                new AccessDeniedException(
                                        TENANT_ACCESS_DENIED_MESSAGE));
    }

    /**
     * Extracts OrderHub's minimized authenticated principal from the request.
     *
     * @param webRequest current request abstraction
     * @return internal authenticated User principal
     */
    private io.github.piresrenan.orderhub.security.application.model.AuthenticatedUserPrincipal
            authenticatedPrincipal(
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

    /**
     * Parses the caller-provided Tenant selector without treating it as proof of
     * Tenant access.
     *
     * <p>Malformed values are intentionally omitted from the generated mismatch
     * exception so rejected Tenant input is not unnecessarily retained in error
     * metadata.
     *
     * @param parameter controller parameter associated with the trusted context
     * @param webRequest current request abstraction
     * @return syntactically valid requested Tenant UUID
     */
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
