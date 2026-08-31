package io.github.piresrenan.orderhub.users.application.service;

import io.github.piresrenan.orderhub.users.application.port.in.CreateUserUseCase;
import io.github.piresrenan.orderhub.users.application.port.out.UserIdGenerator;
import io.github.piresrenan.orderhub.users.application.port.out.UserRepository;
import io.github.piresrenan.orderhub.users.domain.model.User;

public final class CreateUserService implements CreateUserUseCase {

    private final UserRepository userRepository;
    private final UserIdGenerator userIdGenerator;

    /**
     * Creates the User application service using only application-owned output
     * ports.
     *
     * @param userRepository persistence boundary for User aggregates
     * @param userIdGenerator internal identity-generation boundary
     */
    public CreateUserService(
            UserRepository userRepository,
            UserIdGenerator userIdGenerator) {

        this.userRepository = userRepository;
        this.userIdGenerator = userIdGenerator;
    }

    /**
     * Coordinates internal identity generation, domain construction and
     * persistence for one new User.
     *
     * <p>
     * Domain construction occurs before persistence so an invalid generated
     * identity never crosses the repository boundary.
     * </p>
     *
     * @return successfully created and persisted User
     * @throws IllegalArgumentException when the generated identity violates the
     *                                  User invariant
     */
    @Override
    public User create() {
        var user = User.create(
                userIdGenerator.generate());

        return userRepository.save(user);
    }
}
