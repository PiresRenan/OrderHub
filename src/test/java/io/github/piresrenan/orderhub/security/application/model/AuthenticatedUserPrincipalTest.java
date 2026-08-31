package io.github.piresrenan.orderhub.security.application.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.Test;

class AuthenticatedUserPrincipalTest {

    @Test
    void representsAuthenticatedUserByInternalIdentityOnly() {
        // Why: downstream security decisions must rely on OrderHub's internal
        // User identity rather than provider-owned authentication identifiers.
        // Covers: construction and exposure of the authenticated internal User UUID.
        // Prevents: JWT subject, issuer or other provider claims becoming the
        // application's authenticated principal.

        var userId = UUID.randomUUID();

        var principal =
                new AuthenticatedUserPrincipal(
                        userId);

        assertThat(principal.userId())
                .isEqualTo(userId);
    }

    @Test
    void rejectsMissingAuthenticatedUserIdentity() {
        // Why: an authenticated principal without an internal User identity is
        // semantically invalid.
        // Covers: mandatory internal User UUID invariant.
        // Prevents: partially resolved or anonymous authentication state being
        // represented as an authenticated OrderHub User.

        assertThatThrownBy(() ->
                new AuthenticatedUserPrincipal(
                        null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(
                        "Authenticated user id is required");
    }

    @Test
    void containsOnlyInternalUserIdentity() {
        // Why: the principal is the deliberate boundary that removes provider
        // authentication details before downstream application processing.
        // Covers: exact structural contract of one UUID component named userId.
        // Prevents: issuer, subject, token, Jwt or arbitrary claims creeping into
        // the authenticated application principal.

        assertThat(AuthenticatedUserPrincipal.class.isRecord())
                .isTrue();

        assertThat(AuthenticatedUserPrincipal.class.getRecordComponents())
                .singleElement()
                .satisfies(component -> {

                    assertThat(component.getName())
                            .isEqualTo("userId");

                    assertThat(component.getType())
                            .isEqualTo(UUID.class);
                });
    }
}
