package io.github.piresrenan.orderhub.users.application.port.in;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.Test;

class ResolvedUserIdentityTest {

    @Test
    void carriesResolvedInternalUserIdentity() {
        // Why: cross-module consumers need a semantically explicit representation
        // of the internal User identity produced by external identity resolution.
        // Covers: valid ResolvedUserIdentity construction and access.
        // Prevents: raw UUID values losing their User-specific meaning at module
        // boundaries.

        var userId = UUID.randomUUID();

        var identity = new ResolvedUserIdentity(userId);

        assertThat(identity.userId())
                .isEqualTo(userId);
    }

    @Test
    void rejectsMissingInternalUserIdentity() {
        // Why: a successful external identity resolution cannot represent an
        // unknown internal User.
        // Covers: required resolved userId invariant.
        // Prevents: invalid successful-resolution objects reaching security
        // consumers.

        assertThatThrownBy(() -> new ResolvedUserIdentity(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Resolved user id is required");
    }
}
