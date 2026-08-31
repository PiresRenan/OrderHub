package io.github.piresrenan.orderhub.users.application.port.out;

import java.util.Optional;
import java.util.UUID;

import io.github.piresrenan.orderhub.users.domain.model.User;

public interface UserRepository {

    /**
     * Persists one complete User aggregate.
     *
     * @param user valid User aggregate
     * @return persisted User aggregate
     */
    User save(User user);

    /**
     * Finds one User by its internal identity.
     *
     * @param userId internal User identifier
     * @return matching User when present, otherwise empty
     */
    Optional<User> findById(UUID userId);
}
