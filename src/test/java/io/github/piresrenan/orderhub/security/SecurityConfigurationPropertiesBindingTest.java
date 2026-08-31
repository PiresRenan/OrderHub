package io.github.piresrenan.orderhub.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import io.github.piresrenan.orderhub.security.adapter.in.authentication.jwt.JwtResourceServerProperties;
import io.github.piresrenan.orderhub.users.application.port.in.FindTenantMembershipUseCase;
import io.github.piresrenan.orderhub.users.application.port.in.ResolveExternalIdentityUseCase;

class SecurityConfigurationPropertiesBindingTest {

    private static final String ISSUER =
            "https://Issuer.Example.test/Provider";

    private static final String AUDIENCE =
            "OrderHub-API";

    private static final String JWK_SET_URI =
            "https://Issuer.Example.test/Provider/.well-known/jwks.json";

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withUserConfiguration(
                            SecurityConfiguration.class)
                    .withBean(
                            ResolveExternalIdentityUseCase.class,
                            () -> query -> {
                                throw new AssertionError(
                                        "JWT properties binding must not resolve external identity");
                            })
                    .withBean(
                            FindTenantMembershipUseCase.class,
                            () -> query -> {
                                throw new AssertionError(
                                        "JWT properties binding must not resolve tenant membership");
                            });

    @Test
    void bindsCompleteExternalizedJwtTrustConfiguration() {
        // Why: production authentication configuration must be supplied by the
        // Spring Environment rather than constructed with embedded defaults.
        // Covers: real ConfigurationProperties registration and binding for
        // issuer, audience and JWK Set URI.
        // Prevents: a correct record existing in isolation while Spring never
        // registers or populates it in the production composition root.

        contextRunner
                .withPropertyValues(
                        "orderhub.security.jwt.issuer=" + ISSUER,
                        "orderhub.security.jwt.audience=" + AUDIENCE,
                        "orderhub.security.jwt.jwk-set-uri=" + JWK_SET_URI)
                .run(context -> {
                    assertThat(context)
                            .hasNotFailed()
                            .hasSingleBean(
                                    JwtResourceServerProperties.class);

                    var properties =
                            context.getBean(
                                    JwtResourceServerProperties.class);

                    assertThat(properties.issuer())
                            .isEqualTo(ISSUER);

                    assertThat(properties.audience())
                            .isEqualTo(AUDIENCE);

                    assertThat(properties.jwkSetUri())
                            .isEqualTo(JWK_SET_URI);
                });
    }

    @Test
    void failsContextWhenIssuerIsMissing() {
        // Why: Spring registration must preserve the fail-closed constructor
        // contract when deployment configuration omits the trusted issuer.
        // Covers: missing issuer through the real configuration binding path.
        // Prevents: successful application startup with an incomplete issuer
        // trust boundary.

        contextRunner
                .withPropertyValues(
                        "orderhub.security.jwt.audience=" + AUDIENCE,
                        "orderhub.security.jwt.jwk-set-uri=" + JWK_SET_URI)
                .run(context -> {
                    assertThat(context)
                            .hasFailed();

                    assertThat(context.getStartupFailure())
                            .isNotNull()
                            .hasRootCauseInstanceOf(
                                    IllegalArgumentException.class)
                            .hasRootCauseMessage(
                                    "JWT issuer is required");
                });
    }

    @Test
    void failsContextWhenIssuerIsBlank() {
        // Why: a deployment variable that exists but contains no issuer must not
        // satisfy the production authentication contract.
        // Covers: blank issuer through Spring property binding.
        // Prevents: whitespace or empty configuration degrading into startup.

        contextRunner
                .withPropertyValues(
                        "orderhub.security.jwt.issuer=   ",
                        "orderhub.security.jwt.audience=" + AUDIENCE,
                        "orderhub.security.jwt.jwk-set-uri=" + JWK_SET_URI)
                .run(context -> {
                    assertThat(context)
                            .hasFailed();

                    assertThat(context.getStartupFailure())
                            .isNotNull()
                            .hasRootCauseInstanceOf(
                                    IllegalArgumentException.class)
                            .hasRootCauseMessage(
                                    "JWT issuer is required");
                });
    }

    @Test
    void failsContextWhenAudienceIsMissing() {
        // Why: issuer validation without audience binding does not prove that the
        // access token was issued for OrderHub.
        // Covers: missing audience through the real Spring binding path.
        // Prevents: application startup with resource binding accidentally
        // disabled.

        contextRunner
                .withPropertyValues(
                        "orderhub.security.jwt.issuer=" + ISSUER,
                        "orderhub.security.jwt.jwk-set-uri=" + JWK_SET_URI)
                .run(context -> {
                    assertThat(context)
                            .hasFailed();

                    assertThat(context.getStartupFailure())
                            .isNotNull()
                            .hasRootCauseInstanceOf(
                                    IllegalArgumentException.class)
                            .hasRootCauseMessage(
                                    "JWT audience is required");
                });
    }

    @Test
    void failsContextWhenAudienceIsBlank() {
        // Why: an empty audience must behave exactly like missing audience.
        // Covers: blank audience supplied by Spring configuration.
        // Prevents: a deployment placeholder silently disabling audience
        // validation.

        contextRunner
                .withPropertyValues(
                        "orderhub.security.jwt.issuer=" + ISSUER,
                        "orderhub.security.jwt.audience=   ",
                        "orderhub.security.jwt.jwk-set-uri=" + JWK_SET_URI)
                .run(context -> {
                    assertThat(context)
                            .hasFailed();

                    assertThat(context.getStartupFailure())
                            .isNotNull()
                            .hasRootCauseInstanceOf(
                                    IllegalArgumentException.class)
                            .hasRootCauseMessage(
                                    "JWT audience is required");
                });
    }

    @Test
    void failsContextWhenJwkSetUriIsMissing() {
        // Why: signature verification cannot be established without a configured
        // trusted public-key source.
        // Covers: missing JWK Set URI through production-style Spring binding.
        // Prevents: discovery assumptions or embedded signing material becoming
        // an implicit fallback.

        contextRunner
                .withPropertyValues(
                        "orderhub.security.jwt.issuer=" + ISSUER,
                        "orderhub.security.jwt.audience=" + AUDIENCE)
                .run(context -> {
                    assertThat(context)
                            .hasFailed();

                    assertThat(context.getStartupFailure())
                            .isNotNull()
                            .hasRootCauseInstanceOf(
                                    IllegalArgumentException.class)
                            .hasRootCauseMessage(
                                    "JWT JWK Set URI is required");
                });
    }

    @Test
    void failsContextWhenJwkSetUriIsBlank() {
        // Why: an empty JWK endpoint is not a usable cryptographic trust source.
        // Covers: blank JWK Set URI through the real configuration binding path.
        // Prevents: a partially populated environment reaching runtime with no
        // signature-verification source.

        contextRunner
                .withPropertyValues(
                        "orderhub.security.jwt.issuer=" + ISSUER,
                        "orderhub.security.jwt.audience=" + AUDIENCE,
                        "orderhub.security.jwt.jwk-set-uri=   ")
                .run(context -> {
                    assertThat(context)
                            .hasFailed();

                    assertThat(context.getStartupFailure())
                            .isNotNull()
                            .hasRootCauseInstanceOf(
                                    IllegalArgumentException.class)
                            .hasRootCauseMessage(
                                    "JWT JWK Set URI is required");
                });
    }
}
