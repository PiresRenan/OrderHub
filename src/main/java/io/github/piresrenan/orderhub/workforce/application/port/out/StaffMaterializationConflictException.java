package io.github.piresrenan.orderhub.workforce.application.port.out;

/** Existing workforce state cannot satisfy provisioning without an explicit change. */
public final class StaffMaterializationConflictException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    /** Provides a bounded failure without exposing workforce identity or state. */
    public StaffMaterializationConflictException() {
        super("Staff provisioning cannot establish the requested relationship");
    }
}
