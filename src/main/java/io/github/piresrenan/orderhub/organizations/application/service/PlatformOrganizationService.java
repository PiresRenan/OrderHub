package io.github.piresrenan.orderhub.organizations.application.service;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

import io.github.piresrenan.orderhub.authorization.application.port.in.administration.AuthorizeAdministrativeActionUseCase;
import io.github.piresrenan.orderhub.authorization.domain.model.AdministrativeGrant;
import io.github.piresrenan.orderhub.authorization.domain.model.AdministrativeScope;
import io.github.piresrenan.orderhub.authorization.domain.model.AuthorizationDecision;
import io.github.piresrenan.orderhub.authorization.domain.model.PermissionCode;
import io.github.piresrenan.orderhub.organizations.application.model.OrganizationAdministrativeAuditAction;
import io.github.piresrenan.orderhub.organizations.application.model.OrganizationAdministrativeAuditEvidence;
import io.github.piresrenan.orderhub.organizations.application.model.OrganizationAdministrativeAuditOutcome;
import io.github.piresrenan.orderhub.organizations.application.port.in.administration.PlatformOrganizationUseCase;
import io.github.piresrenan.orderhub.organizations.application.port.in.administration.AdministrativeOrganization;
import io.github.piresrenan.orderhub.organizations.application.port.in.administration.PlatformAdministrationAccessDeniedException;
import io.github.piresrenan.orderhub.organizations.application.port.in.administration.OrganizationNotFoundException;
import io.github.piresrenan.orderhub.organizations.application.port.in.administration.AdministrativeChangeResult;
import io.github.piresrenan.orderhub.organizations.application.port.out.OrganizationAdministrativeAuditRepository;
import io.github.piresrenan.orderhub.organizations.application.port.out.OrganizationLifecycleMutationResult;
import io.github.piresrenan.orderhub.organizations.application.port.out.OrganizationLifecycleRepository;
import io.github.piresrenan.orderhub.organizations.application.port.out.OrganizationRepository;
import io.github.piresrenan.orderhub.organizations.application.port.out.OrganizationTransactionExecutor;
import io.github.piresrenan.orderhub.organizations.domain.model.Organization;
import io.github.piresrenan.orderhub.organizations.domain.model.OrganizationStatus;

public final class PlatformOrganizationService implements PlatformOrganizationUseCase {

    private final AuthorizeAdministrativeActionUseCase authorization;
    private final OrganizationRepository organizations;
    private final OrganizationLifecycleRepository lifecycle;
    private final OrganizationTransactionExecutor transactions;
    private final OrganizationAdministrativeAuditRepository audit;
    private final Supplier<UUID> organizationIds;
    private final Supplier<UUID> auditIds;

    public PlatformOrganizationService(
            AuthorizeAdministrativeActionUseCase authorization,
            OrganizationRepository organizations,
            OrganizationLifecycleRepository lifecycle,
            OrganizationTransactionExecutor transactions,
            OrganizationAdministrativeAuditRepository audit,
            Supplier<UUID> organizationIds,
            Supplier<UUID> auditIds) {
        this.authorization = Objects.requireNonNull(authorization, "authorization");
        this.organizations = Objects.requireNonNull(organizations, "organizations");
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.organizationIds = Objects.requireNonNull(organizationIds, "organizationIds");
        this.auditIds = Objects.requireNonNull(auditIds, "auditIds");
    }

    @Override
    public AdministrativeOrganization create(UUID actorUserId, String name, UUID correlationId) {
        require(actorUserId, PermissionCode.PLATFORM_ORGANIZATIONS_MANAGE);
        var organization = Organization.create(organizationIds.get(), name);
        return transactions.execute(() -> {
            var saved = organizations.save(organization);
            audit.append(evidence(actorUserId, saved.id(), correlationId,
                    OrganizationAdministrativeAuditAction.CREATE_ORGANIZATION,
                    OrganizationAdministrativeAuditOutcome.APPLIED,
                    null, OrganizationStatus.ACTIVE));
            return view(saved);
        });
    }

    @Override
    public List<AdministrativeOrganization> list(UUID actorUserId) {
        require(actorUserId, PermissionCode.PLATFORM_ORGANIZATIONS_VIEW);
        return organizations.findAll().stream()
                .map(PlatformOrganizationService::view)
                .toList();
    }

    @Override
    public AdministrativeChangeResult suspend(
            UUID actorUserId, UUID organizationId, UUID correlationId) {
        return setStatus(actorUserId, organizationId, correlationId,
                OrganizationStatus.SUSPENDED,
                OrganizationAdministrativeAuditAction.SUSPEND_ORGANIZATION);
    }

    @Override
    public AdministrativeChangeResult recover(
            UUID actorUserId, UUID organizationId, UUID correlationId) {
        return setStatus(actorUserId, organizationId, correlationId,
                OrganizationStatus.ACTIVE,
                OrganizationAdministrativeAuditAction.RECOVER_ORGANIZATION);
    }

    private AdministrativeChangeResult setStatus(
            UUID actorUserId,
            UUID organizationId,
            UUID correlationId,
            OrganizationStatus desired,
            OrganizationAdministrativeAuditAction action) {
        require(actorUserId, PermissionCode.PLATFORM_ORGANIZATIONS_MANAGE);
        Objects.requireNonNull(organizationId, "organizationId");
        Objects.requireNonNull(correlationId, "correlationId");
        return transactions.execute(() -> {
            var result = lifecycle.setStatus(organizationId, desired);
            if (result == OrganizationLifecycleMutationResult.NOT_FOUND) {
                throw new OrganizationNotFoundException();
            }
            var before = result == OrganizationLifecycleMutationResult.UPDATED
                    ? opposite(desired) : desired;
            var outcome = result == OrganizationLifecycleMutationResult.UPDATED
                    ? OrganizationAdministrativeAuditOutcome.APPLIED
                    : OrganizationAdministrativeAuditOutcome.NO_CHANGE;
            audit.append(evidence(actorUserId, organizationId, correlationId,
                    action, outcome, before, desired));
            return result == OrganizationLifecycleMutationResult.UPDATED
                    ? AdministrativeChangeResult.APPLIED
                    : AdministrativeChangeResult.NO_CHANGE;
        });
    }

    private void require(UUID actorUserId, PermissionCode permission) {
        Objects.requireNonNull(actorUserId, "actorUserId");
        var required = new AdministrativeGrant(
                actorUserId, AdministrativeScope.platform(), permission);
        if (authorization.authorize(required) != AuthorizationDecision.ALLOW) {
            throw new PlatformAdministrationAccessDeniedException();
        }
    }

    private OrganizationAdministrativeAuditEvidence evidence(
            UUID actorUserId,
            UUID organizationId,
            UUID correlationId,
            OrganizationAdministrativeAuditAction action,
            OrganizationAdministrativeAuditOutcome outcome,
            OrganizationStatus before,
            OrganizationStatus after) {
        return new OrganizationAdministrativeAuditEvidence(
                auditIds.get(), actorUserId, organizationId, null, action, outcome,
                before, after, null, null, correlationId);
    }

    private static OrganizationStatus opposite(OrganizationStatus status) {
        return status == OrganizationStatus.ACTIVE
                ? OrganizationStatus.SUSPENDED : OrganizationStatus.ACTIVE;
    }

    private static AdministrativeOrganization view(Organization organization) {
        return new AdministrativeOrganization(
                organization.id(), organization.name(), organization.status().name());
    }
}
