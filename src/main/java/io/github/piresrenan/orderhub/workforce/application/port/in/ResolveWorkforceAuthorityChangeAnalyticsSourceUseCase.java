package io.github.piresrenan.orderhub.workforce.application.port.in;

import java.util.Optional;
import java.util.UUID;

import org.springframework.modulith.NamedInterface;

/**
 * Resolves the bounded analytical view of one committed workforce
 * authority-change audit event.
 *
 * <p>
 * The contract exists so a consumer can obtain the operational evidence behind
 * one audit event without reaching into workforce persistence. Workforce
 * remains the only module that knows how its audit evidence is stored.
 * </p>
 */
@NamedInterface("authority-change-analytics-source")
public interface ResolveWorkforceAuthorityChangeAnalyticsSourceUseCase {

    /**
     * Returns the bounded analytical view of one audit event inside one Tenant.
     *
     * <p>
     * Tenant scope is part of the lookup rather than a later filter, so an
     * audit event belonging to another Tenant is simply absent. An unknown
     * event and a foreign Tenant's event are therefore indistinguishable, and
     * the contract cannot be used to discover that an event exists elsewhere.
     * </p>
     *
     * @param tenantId     Tenant scope of the lookup
     * @param auditEventId identity of the operational audit event
     * @return the bounded source, or empty when this Tenant has no such event
     */
    Optional<WorkforceAuthorityChangeAnalyticsSource> resolve(
            UUID tenantId,
            UUID auditEventId);
}
