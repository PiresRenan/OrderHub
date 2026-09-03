package io.github.piresrenan.orderhub.workforce.application.service;

import io.github.piresrenan.orderhub.authorization.domain.model.PermissionEnvelope;
import io.github.piresrenan.orderhub.workforce.domain.model.EffectiveWorkforceAuthority;
import io.github.piresrenan.orderhub.workforce.domain.policy.WorkforcePermissionCeiling;

/**
 * Applies the current workforce authority ceiling to permissions that survived
 * the authorization module's own durable role/override evaluation.
 *
 * <p>
 * This service does not resolve roles, assignments or overrides and does not
 * query authorization persistence.
 * </p>
 */
public final class BoundedWorkforceAuthorizationService {

    public PermissionEnvelope constrain(
            PermissionEnvelope authorizationCandidate,
            EffectiveWorkforceAuthority workforceAuthority) {

        if (authorizationCandidate == null) {
            throw new IllegalArgumentException(
                    "Authorization candidate envelope is required");
        }

        if (workforceAuthority == null) {
            throw new IllegalArgumentException(
                    "Effective workforce authority is required");
        }

        return WorkforcePermissionCeiling.constrain(
                workforceAuthority.permissionEnvelope(),
                authorizationCandidate.permissions());
    }
}
