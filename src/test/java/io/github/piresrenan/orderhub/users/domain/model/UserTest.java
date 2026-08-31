package io.github.piresrenan.orderhub.users.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.Test;

class UserTest {

    @Test
    void createsUserWithInternalIdentity() {
        // Why: OrderHub needs a stable internal identity independent of any
        // authentication or credential mechanism.
        // Covers: creation of the minimal User aggregate.
        // Prevents: User identity becoming implicitly dependent on email,
        // username or an external identity provider.

        var id = UUID.randomUUID();

        var user = User.create(id);

        assertThat(user.id())
                .isEqualTo(id);
    }

    @Test
    void rejectsMissingUserIdDuringCreation() {
        // Why: an aggregate without identity cannot be referenced deterministically
        // by memberships or future authentication mappings.
        // Covers: required User identity invariant during creation.
        // Prevents: identity-less User state entering the domain.

        assertThatThrownBy(() ->
                User.create(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("User id is required");
    }

    @Test
    void rehydratesPersistedUser() {
        // Why: persistence adapters must reconstruct existing User identity without
        // inventing creation-specific behavior.
        // Covers: explicit User reconstruction contract.
        // Prevents: persistence depending implicitly on the new-aggregate factory.

        var id = UUID.randomUUID();

        var user = User.rehydrate(id);

        assertThat(user.id())
                .isEqualTo(id);
    }

    @Test
    void rejectsMissingUserIdDuringRehydration() {
        // Why: corrupted persisted state must not become a valid domain aggregate.
        // Covers: User invariant enforcement during reconstruction.
        // Prevents: invalid durable identity propagating into application logic.

        assertThatThrownBy(() ->
                User.rehydrate(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("User id is required");
    }
}
