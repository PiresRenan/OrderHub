package io.github.piresrenan.orderhub.workforce.application.port.out;

import java.util.Optional;
import java.util.UUID;

import io.github.piresrenan.orderhub.workforce.application.port.in.WorkforceAuthorityChangeAnalyticsSource;

/**
 * Persistence boundary for reading one bounded workforce authority-change
 * analytical source.
 *
 * <p>
 * The boundary is internal to workforce and is deliberately not part of the
 * module's exported surface, so a consumer depends on the application contract
 * rather than on how the evidence is read.
 * </p>
 */
public interface WorkforceAuthorityChangeAnalyticsSourceRepository {

    /**
     * Reads one Tenant-scoped audit event as the bounded analytical source.
     *
     * <p>
     * Tenant scope belongs to the lookup itself. An event stored for another
     * Tenant is absent rather than filtered afterwards.
     * </p>
     *
     * @param tenantId     Tenant scope of the lookup
     * @param auditEventId identity of the operational audit event
     * @return the bounded source, or empty when this Tenant has no such event
     */
    Optional<WorkforceAuthorityChangeAnalyticsSource> findByTenantAndAuditEvent(
            UUID tenantId,
            UUID auditEventId);
}
