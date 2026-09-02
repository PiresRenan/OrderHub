package io.github.piresrenan.orderhub.workforce.domain.model;

import java.util.UUID;

import io.github.piresrenan.orderhub.authorization.domain.model.AuthorityBand;
import io.github.piresrenan.orderhub.authorization.domain.model.PermissionEnvelope;

/**
 * Explicit before/after organizational authority change.
 */
public record PositionChange(
        UUID staffId,
        UUID tenantId,
        UUID fromPositionId,
        UUID toPositionId,
        AuthorityBand beforeAuthorityBand,
        AuthorityBand afterAuthorityBand,
        PermissionEnvelope beforePermissionEnvelope,
        PermissionEnvelope afterPermissionEnvelope,
        PositionChangeType type) {

    public PositionChange {

        if (staffId == null) {
            throw new IllegalArgumentException(
                    "Staff ID is required");
        }

        if (tenantId == null) {
            throw new IllegalArgumentException(
                    "Tenant ID is required");
        }

        if (fromPositionId == null) {
            throw new IllegalArgumentException(
                    "Source position ID is required");
        }

        if (toPositionId == null) {
            throw new IllegalArgumentException(
                    "Target position ID is required");
        }

        if (beforeAuthorityBand == null
                || afterAuthorityBand == null) {

            throw new IllegalArgumentException(
                    "Before and after authority bands are required");
        }

        if (beforePermissionEnvelope == null
                || afterPermissionEnvelope == null) {

            throw new IllegalArgumentException(
                    "Before and after permission envelopes are required");
        }

        if (type == null) {
            throw new IllegalArgumentException(
                    "Position change type is required");
        }
    }
}
