package io.github.piresrenan.orderhub.users.application.port.in;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.Test;

class CreatedUserIdentityTest {

    @Test
    void carriesCreatedInternalUserIdentity() {
        // Why: cross-module consumers need the internally generated User identity
        // without receiving the User aggregate that produced it.
        // Covers: valid CreatedUserIdentity construction and exact identifier
        // preservation.
        // Prevents: User creation exposing Users domain state, or the generated
        // identifier being altered on its way across the module boundary.

        var userId = UUID.randomUUID();

        var identity = new CreatedUserIdentity(userId);

        assertThat(identity.userId())
                .isEqualTo(userId);
    }

    @Test
    void rejectsMissingInternalUserIdentity() {
        // Why: a successful User creation cannot represent an unknown internal
        // User.
        // Covers: required created userId invariant.
        // Prevents: invalid successful-creation objects reaching future
        // provisioning consumers.

        assertThatThrownBy(() -> new CreatedUserIdentity(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Created user id is required");
    }
}
