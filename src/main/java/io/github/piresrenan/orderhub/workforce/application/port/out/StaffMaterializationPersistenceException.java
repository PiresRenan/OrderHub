package io.github.piresrenan.orderhub.workforce.application.port.out;

/** Technical uncertainty during the workforce-owned provisioning write. */
public final class StaffMaterializationPersistenceException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    /** Keeps the technical cause internal and the boundary message constant. */
    public StaffMaterializationPersistenceException(Throwable cause) {
        super("Staff materialization is unavailable", cause);
    }
}
