package io.github.piresrenan.orderhub.organizations.application.model;

public enum OrganizationAdministrativeAuditAction {
    CREATE_ORGANIZATION,
    SUSPEND_ORGANIZATION,
    RECOVER_ORGANIZATION,
    ATTACH_TENANT,
    MOVE_TENANT,
    DETACH_TENANT
}
