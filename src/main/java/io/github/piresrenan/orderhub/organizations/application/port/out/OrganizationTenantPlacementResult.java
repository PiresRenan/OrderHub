package io.github.piresrenan.orderhub.organizations.application.port.out;

public enum OrganizationTenantPlacementResult {

    ATTACHED,
    ALREADY_ATTACHED,
    MOVED,
    DETACHED,
    ALREADY_UNASSIGNED,
    CONFLICT,
    DESTINATION_NOT_FOUND,
    DESTINATION_SUSPENDED
}
