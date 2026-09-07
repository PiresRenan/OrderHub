package io.github.piresrenan.orderhub.organizations.application.port.out;

public final class OrganizationPersistenceException
        extends RuntimeException {

    public OrganizationPersistenceException(
            Throwable cause) {

        super(
                "Organization persistence operation failed.",
                cause);
    }
}
