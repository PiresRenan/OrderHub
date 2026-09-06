package io.github.piresrenan.orderhub.workforce.application.model;

import java.util.UUID;

import org.springframework.modulith.NamedInterface;

/**
 * Durable notification that workforce appended one privilege-significant audit
 * event.
 *
 * <p>
 * The notification is a locator, not an analytical fact. It carries only the
 * Tenant scope and the identity of the operational audit event, so a consumer
 * must come back through the workforce analytics-source contract to obtain any
 * detail. The operational audit row therefore stays the single authority for
 * what actually happened.
 * </p>
 *
 * <p>
 * Keeping the payload at two opaque identifiers also bounds what reaches shared
 * integration infrastructure: the publication log is durable, replayable and
 * outside the workforce schema, so any component copied into it would become a
 * second, weaker copy of operational evidence.
 * </p>
 *
 * @param tenantId     Tenant that owns the audit event
 * @param auditEventId identity of the operational audit event
 */
@NamedInterface("authority-change-analytics-source")
public record WorkforceAuthorityChangeAuditRecorded(
        UUID tenantId,
        UUID auditEventId) {

    public WorkforceAuthorityChangeAuditRecorded {

        if (tenantId == null) {
            throw new IllegalArgumentException(
                    "Tenant ID is required");
        }

        if (auditEventId == null) {
            throw new IllegalArgumentException(
                    "Audit event ID is required");
        }
    }
}
