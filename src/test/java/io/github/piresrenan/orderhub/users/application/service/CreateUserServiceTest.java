package io.github.piresrenan.orderhub.users.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import io.github.piresrenan.orderhub.users.application.port.out.UserIdGenerator;
import io.github.piresrenan.orderhub.users.application.port.out.UserRepository;
import io.github.piresrenan.orderhub.users.domain.model.User;

class CreateUserServiceTest {

    @Test
    void createsAndPersistsUser() {
        // Why: User creation must coordinate internal identity generation and
        // persistence without depending on authentication mechanisms.
        // Covers: complete CreateUserService happy path.
        // Prevents: application code creating Users without crossing the persistence
        // boundary or embedding credential-provider identity generation.

        var userId = UUID.randomUUID();
        var repository = new RecordingUserRepository();

        UserIdGenerator idGenerator = () -> userId;

        var service = new CreateUserService(
                repository,
                idGenerator);

        var user = service.create();

        assertThat(user.id())
                .isEqualTo(userId);

        assertThat(repository.savedUser)
                .isSameAs(user);

        assertThat(repository.saveCount)
                .isEqualTo(1);
    }

    @Test
    void generatesUserIdExactlyOnce() {
        // Why: one logical User creation must consume exactly one internal identity.
        // Covers: cardinality of UserIdGenerator interaction.
        // Prevents: multiple candidate identities being generated for one User.

        var calls = new AtomicInteger();
        var generatedId = UUID.randomUUID();

        UserIdGenerator idGenerator = () -> {
            calls.incrementAndGet();
            return generatedId;
        };

        var service = new CreateUserService(
                new RecordingUserRepository(),
                idGenerator);

        var user = service.create();

        assertThat(calls)
                .hasValue(1);

        assertThat(user.id())
                .isEqualTo(generatedId);
    }

    @Test
    void doesNotPersistWhenGeneratedIdentityIsInvalid() {
        // Why: persistence must receive only aggregates that satisfy User domain
        // invariants.
        // Covers: ordering between identity generation, domain creation and save.
        // Prevents: invalid generated identity crossing the repository boundary.

        var repository = new RecordingUserRepository();

        UserIdGenerator invalidGenerator = () -> null;

        var service = new CreateUserService(
                repository,
                invalidGenerator);

        org.assertj.core.api.Assertions.assertThatThrownBy(service::create)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("User id is required");

        assertThat(repository.saveCount)
                .isZero();

        assertThat(repository.savedUser)
                .isNull();
    }

    private static final class RecordingUserRepository
            implements UserRepository {

        private User savedUser;
        private int saveCount;

        /**
         * Records the User supplied through the persistence output boundary.
         *
         * @param user valid User requested for persistence
         * @return the same User supplied by the application service
         */
        @Override
        public User save(User user) {
            this.savedUser = user;
            this.saveCount++;

            return user;
        }

        /**
         * Satisfies the lookup contract without introducing read behavior unrelated
         * to User creation.
         *
         * @param userId internal User identifier
         * @return always empty for this focused test double
         */
        @Override
        public Optional<User> findById(UUID userId) {
            return Optional.empty();
        }
    }
}
