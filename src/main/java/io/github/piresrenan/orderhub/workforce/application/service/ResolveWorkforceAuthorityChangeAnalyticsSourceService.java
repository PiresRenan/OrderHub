package io.github.piresrenan.orderhub.workforce.application.service;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import io.github.piresrenan.orderhub.workforce.application.port.in.ResolveWorkforceAuthorityChangeAnalyticsSourceUseCase;
import io.github.piresrenan.orderhub.workforce.application.port.in.WorkforceAuthorityChangeAnalyticsSource;
import io.github.piresrenan.orderhub.workforce.application.port.out.WorkforceAuthorityChangeAnalyticsSourceRepository;

/**
 * Resolves one committed workforce authority-change audit event as its bounded
 * analytical source.
 *
 * <p>
 * The service owns the application boundary and nothing else. It performs no
 * vocabulary translation, invents no occurrence time and resolves no analytical
 * subject identity, because each of those belongs to the consumer rather than
 * to the module that owns the operational evidence.
 * </p>
 */
public final class ResolveWorkforceAuthorityChangeAnalyticsSourceService
        implements ResolveWorkforceAuthorityChangeAnalyticsSourceUseCase {

    private final WorkforceAuthorityChangeAnalyticsSourceRepository repository;

    public ResolveWorkforceAuthorityChangeAnalyticsSourceService(
            WorkforceAuthorityChangeAnalyticsSourceRepository repository) {

        this.repository =
                Objects.requireNonNull(
                        repository,
                        "repository");
    }

    @Override
    public Optional<WorkforceAuthorityChangeAnalyticsSource> resolve(
            UUID tenantId,
            UUID auditEventId) {

        Objects.requireNonNull(
                tenantId,
                "tenantId");

        Objects.requireNonNull(
                auditEventId,
                "auditEventId");

        return repository.findByTenantAndAuditEvent(
                tenantId,
                auditEventId);
    }
}
