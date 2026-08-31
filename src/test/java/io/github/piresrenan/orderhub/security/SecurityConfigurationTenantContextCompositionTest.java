package io.github.piresrenan.orderhub.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import io.github.piresrenan.orderhub.security.application.model.AuthenticatedUserPrincipal;
import io.github.piresrenan.orderhub.security.application.port.in.ResolveTrustedTenantContextQuery;
import io.github.piresrenan.orderhub.security.application.port.in.ResolveTrustedTenantContextUseCase;
import io.github.piresrenan.orderhub.security.application.service.ResolveTrustedTenantContextService;
import io.github.piresrenan.orderhub.users.application.port.in.FindTenantMembershipQuery;
import io.github.piresrenan.orderhub.users.application.port.in.FindTenantMembershipUseCase;
import io.github.piresrenan.orderhub.users.application.port.in.ResolveExternalIdentityUseCase;

class SecurityConfigurationTenantContextCompositionTest {

    private static final String ISSUER =
            "https://issuer.example.test";

    private static final String AUDIENCE =
            "orderhub-api";

    private static final String UNUSED_JWK_SET_URI =
            "https://unused.example.test/jwks";

    @Test
    void composesTrustedTenantResolverFromUsersMembershipBoundary() {
        // Why: HTTP adapters must eventually derive trusted Tenant context through
        // Security's application boundary rather than querying Users directly.
        // Covers: FindTenantMembershipUseCase -> ResolveTrustedTenantContextUseCase
        // production composition and propagation of internal User + requested
        // Tenant identifiers.
        // Prevents: web adapters bypassing Security application logic or gaining
        // knowledge of Users persistence/domain internals.

        var userId =
                UUID.randomUUID();

        var tenantId =
                UUID.randomUUID();

        var observedMembershipQuery =
                new AtomicReference<FindTenantMembershipQuery>();

        FindTenantMembershipUseCase memberships =
                query -> {
                    observedMembershipQuery.set(
                            query);

                    return Optional.empty();
                };

        ResolveExternalIdentityUseCase externalIdentities =
                query ->
                        Optional.empty();

        new ApplicationContextRunner()
                .withUserConfiguration(
                        SecurityConfiguration.class)
                .withBean(
                        ResolveExternalIdentityUseCase.class,
                        () -> externalIdentities)
                .withBean(
                        FindTenantMembershipUseCase.class,
                        () -> memberships)
                .withPropertyValues(
                        "orderhub.security.jwt.issuer="
                                + ISSUER,
                        "orderhub.security.jwt.audience="
                                + AUDIENCE,
                        "orderhub.security.jwt.jwk-set-uri="
                                + UNUSED_JWK_SET_URI)
                .run(context -> {
                    assertThat(context)
                            .hasNotFailed()
                            .hasSingleBean(
                                    ResolveTrustedTenantContextUseCase.class);

                    assertThat(
                            context.getBean(
                                    ResolveTrustedTenantContextUseCase.class))
                            .isInstanceOf(
                                    ResolveTrustedTenantContextService.class);

                    var resolver =
                            context.getBean(
                                    ResolveTrustedTenantContextUseCase.class);

                    var resolved =
                            resolver.resolve(
                                    new ResolveTrustedTenantContextQuery(
                                            new AuthenticatedUserPrincipal(
                                                    userId),
                                            tenantId));

                    assertThat(resolved)
                            .isEmpty();

                    assertThat(observedMembershipQuery)
                            .hasValue(
                                    new FindTenantMembershipQuery(
                                            userId,
                                            tenantId));
                });
    }
}
