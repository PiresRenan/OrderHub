package io.github.piresrenan.orderhub.workforce.application.port.in;

@org.springframework.modulith.NamedInterface("membership-administration")
public final class TenantMembershipAdministrationUnavailableException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    /** Creates the bounded failure classification without exposing private inputs or infrastructure details. */
    public TenantMembershipAdministrationUnavailableException() { super("Tenant membership administration is unavailable"); }
}
