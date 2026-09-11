package io.github.piresrenan.orderhub.workforce.application.port.in;

/** An authorized proof cannot replace existing Staff placement through provisioning. */
@org.springframework.modulith.NamedInterface("staff-provisioning")
public class StaffProvisioningConflictException extends RuntimeException {
    /** Creates the bounded failure classification without exposing private inputs or infrastructure details. */
    public StaffProvisioningConflictException() { super("Staff provisioning cannot establish the requested relationship"); }
}
