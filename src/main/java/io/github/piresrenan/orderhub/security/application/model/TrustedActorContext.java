package io.github.piresrenan.orderhub.security.application.model;

import java.util.UUID;

/**
 * Carries the authenticated internal User and Tenant authority after the exact
 * User/Tenant membership has been proven.
 *
 * <p>
 * This context contains only OrderHub internal identifiers. JWT claims,
 * provider roles, bearer credentials and external identity identifiers are
 * deliberately excluded.
 * </p>
 *
 * @param userId trusted authenticated internal User identifier
 * @param tenantId trusted internal Tenant identifier
 */
public record TrustedActorContext(
        UUID userId,
        UUID tenantId) {

    public TrustedActorContext {

        if (userId == null) {
            throw new IllegalArgumentException(
                    "Trusted actor user id is required");
        }

        if (tenantId == null) {
            throw new IllegalArgumentException(
                    "Trusted actor tenant id is required");
        }
    }
}
