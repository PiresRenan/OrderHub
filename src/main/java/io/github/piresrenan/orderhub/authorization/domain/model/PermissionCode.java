package io.github.piresrenan.orderhub.authorization.domain.model;

import org.springframework.modulith.NamedInterface;

/**
 * System-owned atomic business and administrative authorization vocabulary.
 *
 * <p>
 * Tenant-persona permissions and administrative permissions are deliberately
 * classified through different dimensions. An upper-scope permission never
 * becomes a STAFF permission merely because it is privileged.
 * </p>
 */
@NamedInterface({
    "policy-model",
    "administration"
})
public enum PermissionCode {

    TENANT_MEMBERS_VIEW(AuthorizationPersona.STAFF),
    TENANT_MEMBERS_MANAGE(AuthorizationPersona.STAFF),
    TENANT_ROLES_VIEW(AuthorizationPersona.STAFF),
    TENANT_ROLES_ASSIGN(AuthorizationPersona.STAFF),
    TENANT_PRIVILEGED_ROLES_ASSIGN(AuthorizationPersona.STAFF),

    CATALOG_VIEW(AuthorizationPersona.STAFF),
    CATALOG_MANAGE(AuthorizationPersona.STAFF),
    CATALOG_PRICE_MANAGE(AuthorizationPersona.STAFF),

    INVENTORY_VIEW(AuthorizationPersona.STAFF),
    INVENTORY_RECEIVE(AuthorizationPersona.STAFF),
    INVENTORY_ADJUST(AuthorizationPersona.STAFF),
    INVENTORY_POLICY_MANAGE(AuthorizationPersona.STAFF),

    ORDERS_VIEW(AuthorizationPersona.STAFF),
    ORDERS_CREATE(AuthorizationPersona.STAFF),
    ORDERS_MANAGE(AuthorizationPersona.STAFF),
    ORDERS_APPROVE(AuthorizationPersona.STAFF),

    CUSTOMER_ORDERS_VIEW(AuthorizationPersona.CUSTOMER),
    CUSTOMER_ORDERS_CREATE(AuthorizationPersona.CUSTOMER),

    AUDIT_VIEW(AuthorizationPersona.STAFF),

    PLATFORM_ORGANIZATIONS_VIEW(AdministrativeScopeType.PLATFORM),
    PLATFORM_ORGANIZATIONS_MANAGE(AdministrativeScopeType.PLATFORM),
    PLATFORM_TENANTS_MANAGE(AdministrativeScopeType.PLATFORM),
    PLATFORM_ORGANIZATION_GRANTS_MANAGE(AdministrativeScopeType.PLATFORM),

    ORGANIZATION_TENANTS_VIEW(AdministrativeScopeType.ORGANIZATION);

    private final AuthorizationPersona supportedPersona;
    private final AdministrativeScopeType supportedAdministrativeScope;

    PermissionCode(
            AuthorizationPersona supportedPersona) {

        if (supportedPersona == null) {
            throw new IllegalArgumentException(
                    "Supported authorization persona is required");
        }

        this.supportedPersona =
                supportedPersona;

        this.supportedAdministrativeScope =
                null;
    }

    PermissionCode(
            AdministrativeScopeType supportedAdministrativeScope) {

        if (supportedAdministrativeScope == null) {
            throw new IllegalArgumentException(
                    "Supported administrative scope is required");
        }

        this.supportedPersona =
                null;

        this.supportedAdministrativeScope =
                supportedAdministrativeScope;
    }

    /**
     * Reports whether the current permission is meaningful for one Tenant
     * authorization persona.
     *
     * @param persona authorization persona
     * @return true when this permission may participate in that persona policy
     */
    public boolean supports(
            AuthorizationPersona persona) {

        if (persona == null) {
            throw new IllegalArgumentException(
                    "Authorization persona is required");
        }

        return supportedPersona
                == persona;
    }

    public boolean isAdministrative() {

        return supportedAdministrativeScope
                != null;
    }

    public boolean supportsAdministrativeScope(
            AdministrativeScopeType scopeType) {

        if (scopeType == null) {
            throw new IllegalArgumentException(
                    "Administrative scope type is required");
        }

        return supportedAdministrativeScope
                == scopeType;
    }
}
