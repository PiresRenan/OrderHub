package io.github.piresrenan.orderhub.users.application.port.in;

/**
 * Defines the application boundary for creating one new internally identified
 * OrderHub User.
 */
public interface CreateUserUseCase {

    /**
     * Creates and persists one new internally identified User.
     *
     * <p>
     * The caller receives only the internally generated identifier through an
     * application-owned result. The User aggregate never crosses this
     * boundary.
     * </p>
     *
     * @return internal identity of the successfully created and persisted User
     */
    CreatedUserIdentity create();
}
