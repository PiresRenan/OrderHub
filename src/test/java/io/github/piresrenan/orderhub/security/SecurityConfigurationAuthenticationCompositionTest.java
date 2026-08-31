package io.github.piresrenan.orderhub.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.security.oauth2.jwt.Jwt;

import io.github.piresrenan.orderhub.security.adapter.in.authentication.jwt.AuthenticatedUserJwtAuthenticationConverter;
import io.github.piresrenan.orderhub.security.application.model.AuthenticatedUserPrincipal;
import io.github.piresrenan.orderhub.security.application.port.in.ResolveAuthenticatedUserUseCase;
import io.github.piresrenan.orderhub.security.application.service.ResolveAuthenticatedUserService;
import io.github.piresrenan.orderhub.users.application.port.in.FindTenantMembershipUseCase;
import io.github.piresrenan.orderhub.users.application.port.in.ResolveExternalIdentityUseCase;
import io.github.piresrenan.orderhub.users.application.port.in.ResolvedUserIdentity;

class SecurityConfigurationAuthenticationCompositionTest {

    private static final String ISSUER =
            "https://issuer.example.test";

    private static final String SUBJECT =
            "synthetic-subject-001";

    private static final String AUDIENCE =
            "orderhub-api";

    private static final String UNUSED_JWK_SET_URI =
            "https://unused.example.test/jwks";

    @Test
    void composesAuthenticatedUserResolverFromUsersApplicationBoundary() {
        // Why: the Spring composition root must connect Security's internal
        // authentication application service to the Users-owned external
        // identity resolution contract.
        // Covers: ResolveExternalIdentityUseCase -> ResolveAuthenticatedUserUseCase
        // composition through the production Security configuration.
        // Prevents: HTTP authentication bypassing the Security application layer
        // or directly depending on Users persistence/domain internals.

        contextRunner(
                UUID.randomUUID())
                .run(context -> {
                    assertThat(context)
                            .hasNotFailed()
                            .hasSingleBean(
                                    ResolveAuthenticatedUserUseCase.class);

                    assertThat(
                            context.getBean(
                                    ResolveAuthenticatedUserUseCase.class))
                            .isInstanceOf(
                                    ResolveAuthenticatedUserService.class);
                });
    }

    @Test
    void composesJwtAuthenticationConverterFromInternalUserResolver() {
        // Why: validated JWTs must enter Spring Security through the converter
        // backed by OrderHub's internal User-resolution use case.
        // Covers: complete production composition from Users external identity
        // boundary to AuthenticatedUserPrincipal.
        // Prevents: a default JwtAuthenticationToken retaining JWT claims or
        // external provider identity as the application-facing principal.

        var userId =
                UUID.randomUUID();

        contextRunner(
                userId)
                .run(context -> {
                    assertThat(context)
                            .hasNotFailed()
                            .hasSingleBean(
                                    AuthenticatedUserJwtAuthenticationConverter.class);

                    var converter =
                            context.getBean(
                                    AuthenticatedUserJwtAuthenticationConverter.class);

                    var authentication =
                            converter.convert(
                                    jwt(
                                            ISSUER,
                                            SUBJECT));

                    assertThat(authentication)
                            .isNotNull();

                    assertThat(authentication.getPrincipal())
                            .isEqualTo(
                                    new AuthenticatedUserPrincipal(
                                            userId));

                    assertThat(authentication.getPrincipal())
                            .isInstanceOf(
                                    AuthenticatedUserPrincipal.class);

                    assertThat(authentication.getPrincipal())
                            .isNotInstanceOf(
                                    Jwt.class);
                });
    }

    /**
     * Creates an isolated Security composition context with a synthetic Users
     * application boundary.
     *
     * <p>The configured JWK endpoint is intentionally never contacted because
     * this test exercises post-validation identity composition only.
     *
     * @param userId internal User resolved by the synthetic Users boundary
     * @return isolated production Security composition context
     */
    private ApplicationContextRunner contextRunner(
            UUID userId) {

        ResolveExternalIdentityUseCase externalIdentities =
                query -> {
                    if (ISSUER.equals(
                            query.issuer())
                            && SUBJECT.equals(
                                    query.subject())) {

                        return Optional.of(
                                new ResolvedUserIdentity(
                                        userId));
                    }

                    return Optional.empty();
                };

        return new ApplicationContextRunner()
                .withUserConfiguration(
                        SecurityConfiguration.class)
                .withBean(
                        ResolveExternalIdentityUseCase.class,
                        () -> externalIdentities)
                .withBean(
                        FindTenantMembershipUseCase.class,
                        () -> query -> {
                            throw new AssertionError(
                                    "Authentication composition must not resolve tenant membership");
                        })
                .withPropertyValues(
                        "orderhub.security.jwt.issuer="
                                + ISSUER,
                        "orderhub.security.jwt.audience="
                                + AUDIENCE,
                        "orderhub.security.jwt.jwk-set-uri="
                                + UNUSED_JWK_SET_URI);
    }

    /**
     * Creates a synthetic already-decoded JWT for authentication-composition
     * testing.
     *
     * @param issuer exact external issuer
     * @param subject exact external subject
     * @return synthetic decoded JWT
     */
    private Jwt jwt(
            String issuer,
            String subject) {

        var now =
                Instant.now();

        return Jwt
                .withTokenValue(
                        "synthetic-composition-test-token")
                .header(
                        "alg",
                        "RS256")
                .claim(
                        "iss",
                        issuer)
                .claim(
                        "sub",
                        subject)
                .claim(
                        "aud",
                        AUDIENCE)
                .issuedAt(
                        now.minusSeconds(
                                30))
                .expiresAt(
                        now.plusSeconds(
                                300))
                .build();
    }
}
