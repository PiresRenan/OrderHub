package io.github.piresrenan.orderhub.tenants.application.model;

import java.util.Objects;
import java.util.UUID;

import io.github.piresrenan.orderhub.tenants.domain.model.TenantStatus;

public record TenantAdministrativeAuditEvidence(
        UUID auditEventId,
        UUID actorUserId,
        UUID tenantId,
        TenantAdministrativeAuditAction action,
        TenantAdministrativeAuditOutcome outcome,
        TenantStatus beforeStatus,
        TenantStatus afterStatus,
        UUID correlationId) {

    public TenantAdministrativeAuditEvidence {
        Objects.requireNonNull(auditEventId, "auditEventId");
        Objects.requireNonNull(actorUserId, "actorUserId");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(afterStatus, "afterStatus");
        Objects.requireNonNull(correlationId, "correlationId");
    }
}
