package io.github.piresrenan.orderhub.workforce.application.model;

import java.util.Objects;
import java.util.UUID;

/** Minimal internal attribution; no secret or external provider identity is accepted. */
public record StaffProvisioningEvidence(UUID tenantId, UUID intentId, UUID actorUserId,
        UUID subjectUserId, UUID staffId, Action action, UUID correlationId) {
    /** Bounded successful transitions; failed transactions leave no durable success evidence. */
    public enum Action { ISSUED, COLD_START_ISSUED, CONSUMED, CANCELLED }

    /** Requires exact attribution and a complete result only for consumption. */
    public StaffProvisioningEvidence {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(intentId, "intentId");
        Objects.requireNonNull(actorUserId, "actorUserId");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(correlationId, "correlationId");
        if (action == Action.CONSUMED ? subjectUserId == null || staffId == null : subjectUserId != null || staffId != null) {
            throw new IllegalArgumentException("Provisioning evidence result does not match its action");
        }
    }
}
