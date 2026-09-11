package io.github.piresrenan.orderhub.workforce.application.port.in;

@org.springframework.modulith.NamedInterface("membership-administration")
public final class TenantMembershipAdministrationUnavailableException extends RuntimeException {
    public TenantMembershipAdministrationUnavailableException() { super("Tenant membership administration is unavailable"); }
}
