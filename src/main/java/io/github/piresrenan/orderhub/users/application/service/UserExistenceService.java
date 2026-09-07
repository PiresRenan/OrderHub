package io.github.piresrenan.orderhub.users.application.service;

import java.util.Objects;
import java.util.UUID;

import io.github.piresrenan.orderhub.users.application.port.in.UserExistenceUseCase;
import io.github.piresrenan.orderhub.users.application.port.out.UserRepository;

public final class UserExistenceService implements UserExistenceUseCase {

    private final UserRepository users;

    public UserExistenceService(UserRepository users) {
        this.users = Objects.requireNonNull(users, "users");
    }

    @Override
    public boolean exists(UUID userId) {
        return users.findById(Objects.requireNonNull(userId, "userId")).isPresent();
    }
}
