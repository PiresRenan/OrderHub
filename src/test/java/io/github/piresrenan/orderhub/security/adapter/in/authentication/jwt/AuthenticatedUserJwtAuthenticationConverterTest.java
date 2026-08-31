package io.github.piresrenan.orderhub.security.adapter.in.authentication.jwt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.oauth2.jwt.Jwt;

import io.github.piresrenan.orderhub.security.application.model.AuthenticatedUserPrincipal;
import io.github.piresrenan.orderhub.security.application.port.in.ResolveAuthenticatedUserQuery;
import io.github.piresrenan.orderhub.security.application.port.in.ResolveAuthenticatedUserUseCase;

class AuthenticatedUserJwtAuthenticationConverterTest {

    private static final String ISSUER =
            "https://issuer.example.test";

    private static final String SUBJECT =
            "synthetic-subject-001";

    @Test
    void convertsResolvedExternalIdentityIntoAuthenticatedInternalPrincipal() {
        // Why: successful JWT validation must not leave raw provider identity as
        // the application-facing authenticated principal.
        // Covers: projection from a validated JWT into the internal User
        // principal established by the Security application boundary.
        // Prevents: JWT claims or external subject becoming the principal used
        // by downstream request processing.

        var userId = UUID.randomUUID();

        ResolveAuthenticatedUserUseCase users =
                query -> Optional.of(
                        new AuthenticatedUserPrincipal(
                                userId));

        var converter =
                new AuthenticatedUserJwtAuthenticationConverter(
                        users);

        var authentication =
                converter.convert(
                        jwt(
                                ISSUER,
                                SUBJECT));

        assertThat(authentication)
                .isNotNull();

        assertThat(authentication.isAuthenticated())
                .isTrue();

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

        assertThat(authentication.getCredentials())
                .isNull();

        assertThat(authentication.getAuthorities())
                .isEmpty();
    }

    @Test
    void delegatesExactIssuerAndSubjectWithoutNormalization() {
        // Why: issuer and subject form an external identity key and therefore
        // must retain their exact provider-defined representation.
        // Covers: adapter mapping from JWT claims into the application query.
        // Prevents: case conversion, trimming or claim substitution changing
        // identity semantics before Users resolution.

        var exactIssuer =
                "https://Issuer.Example.test/Provider";

        var exactSubject =
                "Subject-Case_Sensitive:001";

        var captured =
                new AtomicReference<ResolveAuthenticatedUserQuery>();

        ResolveAuthenticatedUserUseCase users =
                query -> {
                    captured.set(query);

                    return Optional.of(
                            new AuthenticatedUserPrincipal(
                                    UUID.randomUUID()));
                };

        var converter =
                new AuthenticatedUserJwtAuthenticationConverter(
                        users);

        converter.convert(
                jwt(
                        exactIssuer,
                        exactSubject));

        assertThat(captured.get())
                .isNotNull();

        assertThat(captured.get().issuer())
                .isEqualTo(exactIssuer);

        assertThat(captured.get().subject())
                .isEqualTo(exactSubject);
    }

    @Test
    void rejectsUnknownExternalIdentityAsGenericAuthenticationFailure() {
        // Why: a cryptographically valid JWT is not sufficient when its external
        // identity has no binding to an internal OrderHub User.
        // Covers: translation of unresolved application identity into an
        // authentication-framework failure.
        // Prevents: unknown external identities becoming authenticated and
        // identity-enumeration details reaching the public boundary.

        ResolveAuthenticatedUserUseCase users =
                query -> Optional.empty();

        var converter =
                new AuthenticatedUserJwtAuthenticationConverter(
                        users);

        assertThatThrownBy(() ->
                converter.convert(
                        jwt(
                                ISSUER,
                                SUBJECT)))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessage("Bearer authentication failed")
                .hasMessageNotContaining(ISSUER)
                .hasMessageNotContaining(SUBJECT);
    }

    @Test
    void rejectsJwtWithoutSubjectAsGenericAuthenticationFailure() {
        // Why: external identity resolution requires both issuer and subject even
        // after cryptographic token processing succeeds.
        // Covers: structurally incomplete external identity at the framework
        // adapter boundary.
        // Prevents: malformed identity claims escaping as internal exceptions or
        // producing ambiguous authenticated state.

        ResolveAuthenticatedUserUseCase users =
                query -> {
                    throw new AssertionError(
                            "Incomplete identity must not reach application resolution");
                };

        var converter =
                new AuthenticatedUserJwtAuthenticationConverter(
                        users);

        assertThatThrownBy(() ->
                converter.convert(
                        jwtWithoutSubject(
                                ISSUER)))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessage("Bearer authentication failed")
                .hasMessageNotContaining(ISSUER);
    }

    @Test
    void authenticationNameDoesNotExposeExternalOrInternalIdentity() {
        // Why: Authentication#getName can be consumed by framework logging and
        // diagnostics even when application code never requests it directly.
        // Covers: privacy-safe authentication identity presentation.
        // Prevents: external subject or internal User UUID leaking through the
        // default Authentication name representation.

        var userId = UUID.randomUUID();

        ResolveAuthenticatedUserUseCase users =
                query -> Optional.of(
                        new AuthenticatedUserPrincipal(
                                userId));

        var converter =
                new AuthenticatedUserJwtAuthenticationConverter(
                        users);

        var authentication =
                converter.convert(
                        jwt(
                                ISSUER,
                                SUBJECT));

        assertThat(authentication.getName())
                .isEqualTo("authenticated-user")
                .doesNotContain(SUBJECT)
                .doesNotContain(userId.toString());
    }

    @Test
    void rejectsMissingAuthenticatedUserResolver() {
        // Why: JWT authentication must fail closed when the internal identity
        // resolution boundary is unavailable.
        // Covers: mandatory application dependency.
        // Prevents: constructing an authentication adapter capable of bypassing
        // internal User resolution.

        assertThatThrownBy(() ->
                new AuthenticatedUserJwtAuthenticationConverter(
                        null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * Creates a synthetic already-decoded JWT for adapter-level identity tests.
     *
     * <p>Cryptographic validation is intentionally outside this unit test and is
     * independently covered by JwtValidationPolicyTest.
     *
     * @param issuer exact external issuer claim
     * @param subject exact external subject claim
     * @return synthetic decoded JWT
     */
    private Jwt jwt(
            String issuer,
            String subject) {

        var now = Instant.now();

        return Jwt
                .withTokenValue(
                        "synthetic-adapter-test-token")
                .header(
                        "alg",
                        "RS256")
                .claim(
                        "iss",
                        issuer)
                .claim(
                        "sub",
                        subject)
                .issuedAt(
                        now.minusSeconds(30))
                .expiresAt(
                        now.plusSeconds(300))
                .build();
    }

    /**
     * Creates a decoded JWT lacking the subject required for external identity.
     *
     * @param issuer external issuer claim
     * @return synthetic decoded JWT without subject
     */
    private Jwt jwtWithoutSubject(
            String issuer) {

        var now = Instant.now();

        return Jwt
                .withTokenValue(
                        "synthetic-incomplete-adapter-test-token")
                .header(
                        "alg",
                        "RS256")
                .claim(
                        "iss",
                        issuer)
                .issuedAt(
                        now.minusSeconds(30))
                .expiresAt(
                        now.plusSeconds(300))
                .build();
    }
}
