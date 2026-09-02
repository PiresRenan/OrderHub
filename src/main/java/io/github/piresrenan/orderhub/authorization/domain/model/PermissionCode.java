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

    TENANT_MEMBERS_VIEW,
    TENANT_MEMBERS_MANAGE,
    TENANT_ROLES_VIEW,
    TENANT_ROLES_ASSIGN,
    TENANT_PRIVILEGED_ROLES_ASSIGN,

    CATALOG_VIEW,
    CATALOG_MANAGE,
    CATALOG_PRICE_MANAGE,

    INVENTORY_VIEW,
    INVENTORY_RECEIVE,
    INVENTORY_ADJUST,
    INVENTORY_POLICY_MANAGE,

    ORDERS_VIEW,
    ORDERS_CREATE,
    ORDERS_MANAGE,
    ORDERS_APPROVE,

    AUDIT_VIEW;

    /**
     * Reports whether the current permission is meaningful for one persona.
     *
     * <p>
     * OH-013 initially defines employee/business administration permissions.
     * Customer-owned resource permissions are introduced only when the
     * corresponding Customer use cases exist.
     * </p>
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

        return persona
                == AuthorizationPersona.STAFF;
    }
}
