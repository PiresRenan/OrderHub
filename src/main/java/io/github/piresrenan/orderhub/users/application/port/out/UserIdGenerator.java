package io.github.piresrenan.orderhub.users.application.port.out;

import java.util.UUID;

@FunctionalInterface
public interface UserIdGenerator {

    /**
     * Generates the opaque internal identity for one new User.
     *
     * @return newly generated User identifier
     */
    UUID generate();
}
