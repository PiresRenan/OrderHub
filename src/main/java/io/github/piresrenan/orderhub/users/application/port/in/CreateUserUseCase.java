package io.github.piresrenan.orderhub.users.application.port.in;

import io.github.piresrenan.orderhub.users.domain.model.User;

public interface CreateUserUseCase {

    /**
     * Creates one new internally identified User.
     *
     * @return successfully created and persisted User aggregate
     */
    User create();
}
