package io.github.piresrenan.orderhub.users.application.port.in;

import java.util.UUID;

public interface UserExistenceUseCase {
    boolean exists(UUID userId);
}
