package io.github.piresrenan.orderhub.authorization.domain.model;

import org.springframework.modulith.NamedInterface;

/**
 * System-owned atomic business authorization vocabulary.
 *
 * <p>
 * Tenants may later compose these permissions into constrained custom roles,
 * but cannot invent new executable permission codes.
 * </p>
 */
@NamedInterface("policy-model")
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

    AUDIT_VIEW(AuthorizationPersona.STAFF);

    private final AuthorizationPersona supportedPersona;

    PermissionCode(
            AuthorizationPersona supportedPersona) {

        this.supportedPersona =
                supportedPersona;
    }

    /**
     * Reports whether the current permission is meaningful for one persona.
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
}
