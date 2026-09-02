package io.github.piresrenan.orderhub.workforce.domain.policy;

import java.util.Collection;
import java.util.stream.Collectors;

import io.github.piresrenan.orderhub.authorization.domain.model.PermissionCode;
import io.github.piresrenan.orderhub.authorization.domain.model.PermissionEnvelope;

/**
 * Applies the current workforce ceiling to candidate authorization permissions.
 */
public final class WorkforcePermissionCeiling {

    private WorkforcePermissionCeiling() {
    }

    public static PermissionEnvelope constrain(
            PermissionEnvelope workforceCeiling,
            Collection<PermissionCode> candidatePermissions) {

        if (workforceCeiling == null) {
            throw new IllegalArgumentException(
                    "Workforce permission ceiling is required");
        }

        if (candidatePermissions == null) {
            throw new IllegalArgumentException(
                    "Candidate permissions are required");
        }

        var effective =
                candidatePermissions.stream()
                        .map(permission -> {

                            if (permission == null) {
                                throw new IllegalArgumentException(
                                        "Candidate permissions cannot contain null");
                            }

                            return permission;
                        })
                        .filter(
                                workforceCeiling::allows)
                        .collect(
                                Collectors.toUnmodifiableSet());

        return PermissionEnvelope.of(
                effective);
    }
}
