package io.github.piresrenan.orderhub.organizations.application.model;

import java.util.Objects;
import java.util.UUID;

import io.github.piresrenan.orderhub.organizations.domain.model.OrganizationStatus;

public record OrganizationAdministrativeAuditEvidence(
        UUID auditEventId,
        UUID actorUserId,
        UUID organizationId,
        UUID tenantId,
        OrganizationAdministrativeAuditAction action,
        OrganizationAdministrativeAuditOutcome outcome,
        OrganizationStatus beforeOrganizationStatus,
        OrganizationStatus afterOrganizationStatus,
        UUID beforePlacementOrganizationId,
        UUID afterPlacementOrganizationId,
        UUID correlationId) {

    public OrganizationAdministrativeAuditEvidence {
        Objects.requireNonNull(auditEventId, "auditEventId");
        Objects.requireNonNull(actorUserId, "actorUserId");
        Objects.requireNonNull(organizationId, "organizationId");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(correlationId, "correlationId");
    }
}
