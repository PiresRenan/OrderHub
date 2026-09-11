package io.github.piresrenan.orderhub.workforce.application.port.in;

@org.springframework.modulith.NamedInterface("membership-administration")
public final class TenantMembershipAdministrationUnavailableException extends RuntimeException {
    /** Creates the bounded failure classification without exposing private inputs or infrastructure details. */
    public TenantMembershipAdministrationUnavailableException() { super("Tenant membership administration is unavailable"); }
}
