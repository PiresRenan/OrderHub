package io.github.piresrenan.orderhub.security.application.port.in;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ResolveAuthenticatedUserQueryTest {

    @Test
    void preservesExternalIdentityExactly() {
        // Why: issuer and subject are provider-owned identity values whose exact
        // representation participates in identity resolution.
        // Covers: construction without trimming, lowercasing or normalization.
        // Prevents: the Security module changing external identity semantics before
        // delegating resolution to Users.

        var issuer = "https://Issuer.Example.test/Identity/";
        var subject = " Synthetic-Subject:Case-Sensitive ";

        var query = new ResolveAuthenticatedUserQuery(
                issuer,
                subject);

        assertThat(query.issuer())
                .isEqualTo(issuer);

        assertThat(query.subject())
                .isEqualTo(subject);
    }

    @Test
    void rejectsMissingIssuer() {
        // Why: external identity resolution requires the authenticated token issuer.
        // Covers: null issuer validation at the Security application boundary.
        // Prevents: incomplete provider identity reaching Users persistence lookup.

        assertThatThrownBy(() ->
                new ResolveAuthenticatedUserQuery(
                        null,
                        "synthetic-subject"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(
                        "External identity issuer is required");
    }

    @Test
    void rejectsBlankIssuer() {
        // Why: an issuer containing no meaningful characters cannot identify an
        // authentication authority.
        // Covers: blank issuer validation without normalization.
        // Prevents: meaningless issuer values entering identity resolution.

        assertThatThrownBy(() ->
                new ResolveAuthenticatedUserQuery(
                        "   ",
                        "synthetic-subject"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(
                        "External identity issuer is required");
    }

    @Test
    void rejectsMissingSubject() {
        // Why: authentication cannot be mapped to an internal User without the
        // provider subject.
        // Covers: null subject validation.
        // Prevents: partially resolved authentication identities reaching Users.

        assertThatThrownBy(() ->
                new ResolveAuthenticatedUserQuery(
                        "https://issuer.example.test",
                        null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(
                        "External identity subject is required");
    }

    @Test
    void rejectsBlankSubject() {
        // Why: a blank provider subject cannot identify an external principal.
        // Covers: blank subject validation without trimming valid values.
        // Prevents: authentication proceeding with an unusable external identity.

        assertThatThrownBy(() ->
                new ResolveAuthenticatedUserQuery(
                        "https://issuer.example.test",
                        "   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(
                        "External identity subject is required");
    }
}
