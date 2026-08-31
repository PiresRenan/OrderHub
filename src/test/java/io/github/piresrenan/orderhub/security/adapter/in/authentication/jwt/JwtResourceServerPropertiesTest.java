package io.github.piresrenan.orderhub.security.adapter.in.authentication.jwt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class JwtResourceServerPropertiesTest {

    @Test
    void retainsExactExternalizedSecurityConfiguration() {
        // Why: issuer, audience and JWK location define the production trust
        // boundary and must come from deployment configuration.
        // Covers: exact preservation of all JWT resource-server settings.
        // Prevents: hidden defaults or normalization changing authentication
        // semantics between environments.

        var issuer =
                "https://Issuer.Example.test/Provider";

        var audience =
                "OrderHub-API";

        var jwkSetUri =
                "https://Issuer.Example.test/Provider/.well-known/jwks.json";

        var properties =
                new JwtResourceServerProperties(
                        issuer,
                        audience,
                        jwkSetUri);

        assertThat(properties.issuer())
                .isEqualTo(issuer);

        assertThat(properties.audience())
                .isEqualTo(audience);

        assertThat(properties.jwkSetUri())
                .isEqualTo(jwkSetUri);
    }

    @Test
    void rejectsMissingIssuer() {
        // Why: without a configured trusted issuer, signed tokens from an
        // unintended identity domain could become acceptable.
        // Covers: mandatory issuer configuration.
        // Prevents: production startup with an incomplete trust boundary.

        assertThatThrownBy(() ->
                new JwtResourceServerProperties(
                        null,
                        "orderhub-api",
                        "https://issuer.example.test/jwks"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsBlankIssuer() {
        // Why: an empty placeholder must not count as production security
        // configuration.
        // Covers: blank issuer values supplied by environment/configuration.
        // Prevents: deployment mistakes degrading into weaker authentication.

        assertThatThrownBy(() ->
                new JwtResourceServerProperties(
                        "   ",
                        "orderhub-api",
                        "https://issuer.example.test/jwks"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsMissingAudience() {
        // Why: signature and issuer validation alone do not prove that a token
        // was issued for the OrderHub API.
        // Covers: mandatory audience configuration.
        // Prevents: valid tokens intended for another resource authenticating
        // to OrderHub.

        assertThatThrownBy(() ->
                new JwtResourceServerProperties(
                        "https://issuer.example.test",
                        null,
                        "https://issuer.example.test/jwks"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsBlankAudience() {
        // Why: a blank audience is equivalent to having no resource binding.
        // Covers: empty deployment-provided audience configuration.
        // Prevents: silently disabling the audience requirement.

        assertThatThrownBy(() ->
                new JwtResourceServerProperties(
                        "https://issuer.example.test",
                        "   ",
                        "https://issuer.example.test/jwks"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsMissingJwkSetUri() {
        // Why: the Resource Server requires a trusted source of public keys for
        // cryptographic signature verification.
        // Covers: mandatory externalized JWK Set location.
        // Prevents: fallback signing keys, development JWK endpoints or
        // discovery assumptions entering production implicitly.

        assertThatThrownBy(() ->
                new JwtResourceServerProperties(
                        "https://issuer.example.test",
                        "orderhub-api",
                        null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsBlankJwkSetUri() {
        // Why: a present-but-empty deployment variable must fail exactly like a
        // missing JWK configuration.
        // Covers: blank JWK Set location.
        // Prevents: partially configured deployments reaching runtime before
        // discovering that signature verification cannot be established.

        assertThatThrownBy(() ->
                new JwtResourceServerProperties(
                        "https://issuer.example.test",
                        "orderhub-api",
                        "   "))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
