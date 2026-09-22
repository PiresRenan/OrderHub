package io.github.piresrenan.orderhub.organizations.application.port.out;

public final class OrganizationPersistenceException
        extends RuntimeException {
    private static final long serialVersionUID = 1L;


    public OrganizationPersistenceException(
            Throwable cause) {

        super(
                "Organization persistence operation failed.",
                cause);
    }
}
