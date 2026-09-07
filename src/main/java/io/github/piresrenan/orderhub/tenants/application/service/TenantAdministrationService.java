package io.github.piresrenan.orderhub.tenants.application.service;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

import io.github.piresrenan.orderhub.authorization.application.port.in.administration.AuthorizeAdministrativeActionUseCase;
import io.github.piresrenan.orderhub.authorization.domain.model.AdministrativeGrant;
import io.github.piresrenan.orderhub.authorization.domain.model.AdministrativeScope;
import io.github.piresrenan.orderhub.authorization.domain.model.AuthorizationDecision;
import io.github.piresrenan.orderhub.authorization.domain.model.PermissionCode;
import io.github.piresrenan.orderhub.tenants.application.model.TenantAdministrativeAuditAction;
import io.github.piresrenan.orderhub.tenants.application.model.TenantAdministrativeAuditEvidence;
import io.github.piresrenan.orderhub.tenants.application.model.TenantAdministrativeAuditOutcome;
import io.github.piresrenan.orderhub.tenants.application.port.in.administration.PlatformTenantUseCase;
import io.github.piresrenan.orderhub.tenants.application.port.in.administration.AdministrativeTenant;
import io.github.piresrenan.orderhub.tenants.application.port.in.administration.TenantAdministrationAccessDeniedException;
import io.github.piresrenan.orderhub.tenants.application.port.in.administration.TenantAdministrationNotFoundException;
import io.github.piresrenan.orderhub.tenants.application.port.in.administration.AdministrativeChangeResult;
import io.github.piresrenan.orderhub.tenants.application.port.out.TenantAdministrativeAuditRepository;
import io.github.piresrenan.orderhub.tenants.application.port.out.TenantLifecycleMutationResult;
import io.github.piresrenan.orderhub.tenants.application.port.out.TenantLifecycleRepository;
import io.github.piresrenan.orderhub.tenants.application.port.out.TenantRepository;
import io.github.piresrenan.orderhub.tenants.application.port.out.TenantTransactionExecutor;
import io.github.piresrenan.orderhub.tenants.domain.model.Tenant;
import io.github.piresrenan.orderhub.tenants.domain.model.TenantStatus;

public final class TenantAdministrationService implements PlatformTenantUseCase {

    private final AuthorizeAdministrativeActionUseCase authorization;
    private final TenantRepository tenants;
    private final TenantLifecycleRepository lifecycle;
    private final TenantTransactionExecutor transactions;
    private final TenantAdministrativeAuditRepository audit;
    private final Supplier<UUID> tenantIds;
    private final Supplier<UUID> auditIds;

    public TenantAdministrationService(
            AuthorizeAdministrativeActionUseCase authorization,
            TenantRepository tenants,
            TenantLifecycleRepository lifecycle,
            TenantTransactionExecutor transactions,
            TenantAdministrativeAuditRepository audit,
            Supplier<UUID> tenantIds,
            Supplier<UUID> auditIds) {
        this.authorization = Objects.requireNonNull(authorization, "authorization");
        this.tenants = Objects.requireNonNull(tenants, "tenants");
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.tenantIds = Objects.requireNonNull(tenantIds, "tenantIds");
        this.auditIds = Objects.requireNonNull(auditIds, "auditIds");
    }

    @Override
    public AdministrativeTenant create(UUID actorUserId, String name, UUID correlationId) {
        require(actorUserId);
        Objects.requireNonNull(correlationId, "correlationId");
        var tenant = Tenant.create(tenantIds.get(), name);
        return transactions.execute(() -> {
            var saved = tenants.save(tenant);
            audit.append(evidence(actorUserId, saved.id(), correlationId,
                    TenantAdministrativeAuditAction.CREATE_TENANT,
                    TenantAdministrativeAuditOutcome.APPLIED,
                    null, TenantStatus.ACTIVE));
            return view(saved);
        });
    }

    @Override
    public AdministrativeChangeResult suspend(
            UUID actorUserId, UUID tenantId, UUID correlationId) {
        return setStatus(actorUserId, tenantId, correlationId,
                TenantStatus.SUSPENDED,
                TenantAdministrativeAuditAction.SUSPEND_TENANT);
    }

    @Override
    public AdministrativeChangeResult recover(
            UUID actorUserId, UUID tenantId, UUID correlationId) {
        return setStatus(actorUserId, tenantId, correlationId,
                TenantStatus.ACTIVE,
                TenantAdministrativeAuditAction.RECOVER_TENANT);
    }

    private AdministrativeChangeResult setStatus(
            UUID actorUserId,
            UUID tenantId,
            UUID correlationId,
            TenantStatus desired,
            TenantAdministrativeAuditAction action) {
        require(actorUserId);
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(correlationId, "correlationId");
        return transactions.execute(() -> {
            var result = lifecycle.setStatus(tenantId, desired);
            if (result == TenantLifecycleMutationResult.NOT_FOUND) {
                throw new TenantAdministrationNotFoundException();
            }
            var before = result == TenantLifecycleMutationResult.UPDATED
                    ? opposite(desired) : desired;
            var outcome = result == TenantLifecycleMutationResult.UPDATED
                    ? TenantAdministrativeAuditOutcome.APPLIED
                    : TenantAdministrativeAuditOutcome.NO_CHANGE;
            audit.append(evidence(actorUserId, tenantId, correlationId,
                    action, outcome, before, desired));
            return result == TenantLifecycleMutationResult.UPDATED
                    ? AdministrativeChangeResult.APPLIED
                    : AdministrativeChangeResult.NO_CHANGE;
        });
    }

    private void require(UUID actorUserId) {
        Objects.requireNonNull(actorUserId, "actorUserId");
        var required = new AdministrativeGrant(
                actorUserId,
                AdministrativeScope.platform(),
                PermissionCode.PLATFORM_TENANTS_MANAGE);
        if (authorization.authorize(required) != AuthorizationDecision.ALLOW) {
            throw new TenantAdministrationAccessDeniedException();
        }
    }

    private TenantAdministrativeAuditEvidence evidence(
            UUID actorUserId,
            UUID tenantId,
            UUID correlationId,
            TenantAdministrativeAuditAction action,
            TenantAdministrativeAuditOutcome outcome,
            TenantStatus before,
            TenantStatus after) {
        return new TenantAdministrativeAuditEvidence(
                auditIds.get(), actorUserId, tenantId, action, outcome,
                before, after, correlationId);
    }

    private static TenantStatus opposite(TenantStatus status) {
        return status == TenantStatus.ACTIVE
                ? TenantStatus.SUSPENDED : TenantStatus.ACTIVE;
    }

    private static AdministrativeTenant view(Tenant tenant) {
        return new AdministrativeTenant(
                tenant.id(), tenant.name(), tenant.status().name());
    }
}
