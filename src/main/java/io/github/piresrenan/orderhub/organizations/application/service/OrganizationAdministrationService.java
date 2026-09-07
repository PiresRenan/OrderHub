package io.github.piresrenan.orderhub.organizations.application.service;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

import io.github.piresrenan.orderhub.authorization.application.port.in.administration.AuthorizeAdministrativeActionUseCase;
import io.github.piresrenan.orderhub.authorization.application.port.in.administration.MutateAdministrativeGrantUseCase;
import io.github.piresrenan.orderhub.authorization.domain.model.AdministrativeGrant;
import io.github.piresrenan.orderhub.authorization.domain.model.AdministrativeScope;
import io.github.piresrenan.orderhub.authorization.domain.model.AuthorizationDecision;
import io.github.piresrenan.orderhub.authorization.domain.model.PermissionCode;
import io.github.piresrenan.orderhub.organizations.application.model.OrganizationAdministrativeAuditAction;
import io.github.piresrenan.orderhub.organizations.application.model.OrganizationAdministrativeAuditEvidence;
import io.github.piresrenan.orderhub.organizations.application.model.OrganizationAdministrativeAuditOutcome;
import io.github.piresrenan.orderhub.organizations.application.port.in.administration.AdministrationAccessDeniedException;
import io.github.piresrenan.orderhub.organizations.application.port.in.administration.AdministrativeConflictException;
import io.github.piresrenan.orderhub.organizations.application.port.in.administration.AdministrativeTargetNotFoundException;
import io.github.piresrenan.orderhub.organizations.application.port.in.administration.OrganizationAdministrationUseCase;
import io.github.piresrenan.orderhub.organizations.application.port.in.administration.OrganizationTenantSummary;
import io.github.piresrenan.orderhub.organizations.application.port.in.administration.OrganizationUnavailableException;
import io.github.piresrenan.orderhub.organizations.application.port.out.OrganizationAdministrativeAuditRepository;
import io.github.piresrenan.orderhub.organizations.application.port.out.OrganizationRepository;
import io.github.piresrenan.orderhub.organizations.application.port.out.OrganizationTenantPlacementRepository;
import io.github.piresrenan.orderhub.organizations.application.port.out.OrganizationTenantPlacementResult;
import io.github.piresrenan.orderhub.organizations.application.port.out.OrganizationTransactionExecutor;
import io.github.piresrenan.orderhub.organizations.domain.model.OrganizationStatus;
import io.github.piresrenan.orderhub.tenants.application.port.in.administration.FindTenantAdministrativeMetadataUseCase;
import io.github.piresrenan.orderhub.users.application.port.in.UserExistenceUseCase;

public final class OrganizationAdministrationService
        implements OrganizationAdministrationUseCase {

    private final AuthorizeAdministrativeActionUseCase authorization;
    private final MutateAdministrativeGrantUseCase grants;
    private final OrganizationRepository organizations;
    private final OrganizationTenantPlacementRepository placements;
    private final FindTenantAdministrativeMetadataUseCase tenants;
    private final UserExistenceUseCase users;
    private final OrganizationTransactionExecutor transactions;
    private final OrganizationAdministrativeAuditRepository audit;
    private final Supplier<UUID> auditIds;

    public OrganizationAdministrationService(
            AuthorizeAdministrativeActionUseCase authorization,
            MutateAdministrativeGrantUseCase grants,
            OrganizationRepository organizations,
            OrganizationTenantPlacementRepository placements,
            FindTenantAdministrativeMetadataUseCase tenants,
            UserExistenceUseCase users,
            OrganizationTransactionExecutor transactions,
            OrganizationAdministrativeAuditRepository audit,
            Supplier<UUID> auditIds) {
        this.authorization = Objects.requireNonNull(authorization, "authorization");
        this.grants = Objects.requireNonNull(grants, "grants");
        this.organizations = Objects.requireNonNull(organizations, "organizations");
        this.placements = Objects.requireNonNull(placements, "placements");
        this.tenants = Objects.requireNonNull(tenants, "tenants");
        this.users = Objects.requireNonNull(users, "users");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.auditIds = Objects.requireNonNull(auditIds, "auditIds");
    }

    @Override
    public void attachTenant(UUID actor, UUID organizationId, UUID tenantId, UUID correlationId) {
        requirePlatform(actor, PermissionCode.PLATFORM_TENANTS_MANAGE);
        requireTenant(tenantId);
        transactions.execute(() -> {
            var result = placements.attach(tenantId, organizationId);
            switch (result) {
                case ATTACHED -> appendPlacement(actor, organizationId, tenantId, correlationId,
                        OrganizationAdministrativeAuditAction.ATTACH_TENANT,
                        OrganizationAdministrativeAuditOutcome.APPLIED, null, organizationId);
                case ALREADY_ATTACHED -> appendPlacement(actor, organizationId, tenantId, correlationId,
                        OrganizationAdministrativeAuditAction.ATTACH_TENANT,
                        OrganizationAdministrativeAuditOutcome.NO_CHANGE, organizationId, organizationId);
                case DESTINATION_NOT_FOUND -> throw new AdministrativeTargetNotFoundException();
                default -> throw new AdministrativeConflictException();
            }
            return result;
        });
    }

    @Override
    public void moveTenant(UUID actor, UUID sourceId, UUID destinationId,
            UUID tenantId, UUID correlationId) {
        requirePlatform(actor, PermissionCode.PLATFORM_TENANTS_MANAGE);
        if (Objects.equals(sourceId, destinationId)) {
            throw new AdministrativeConflictException();
        }
        requireTenant(tenantId);
        transactions.execute(() -> {
            var result = placements.move(tenantId, sourceId, destinationId);
            if (result == OrganizationTenantPlacementResult.MOVED) {
                appendPlacement(actor, destinationId, tenantId, correlationId,
                        OrganizationAdministrativeAuditAction.MOVE_TENANT,
                        OrganizationAdministrativeAuditOutcome.APPLIED, sourceId, destinationId);
            } else if (result == OrganizationTenantPlacementResult.DESTINATION_NOT_FOUND) {
                throw new AdministrativeTargetNotFoundException();
            } else {
                throw new AdministrativeConflictException();
            }
            return result;
        });
    }

    @Override
    public void detachTenant(UUID actor, UUID organizationId,
            UUID tenantId, UUID correlationId) {
        requirePlatform(actor, PermissionCode.PLATFORM_TENANTS_MANAGE);
        requireTenant(tenantId);
        transactions.execute(() -> {
            var result = placements.detach(tenantId, organizationId);
            if (result == OrganizationTenantPlacementResult.DETACHED) {
                appendPlacement(actor, organizationId, tenantId, correlationId,
                        OrganizationAdministrativeAuditAction.DETACH_TENANT,
                        OrganizationAdministrativeAuditOutcome.APPLIED, organizationId, null);
            } else if (result == OrganizationTenantPlacementResult.ALREADY_UNASSIGNED) {
                appendPlacement(actor, organizationId, tenantId, correlationId,
                        OrganizationAdministrativeAuditAction.DETACH_TENANT,
                        OrganizationAdministrativeAuditOutcome.NO_CHANGE, null, null);
            } else {
                throw new AdministrativeConflictException();
            }
            return result;
        });
    }

    @Override
    public void grant(UUID actor, UUID organizationId, UUID userId,
            String permissionCode, UUID correlationId) {
        mutateGrant(true, actor, organizationId, userId, permissionCode, correlationId);
    }

    @Override
    public void revoke(UUID actor, UUID organizationId, UUID userId,
            String permissionCode, UUID correlationId) {
        mutateGrant(false, actor, organizationId, userId, permissionCode, correlationId);
    }

    private void mutateGrant(boolean granting, UUID actor, UUID organizationId,
            UUID userId, String permissionCode, UUID correlationId) {
        requirePlatform(actor, PermissionCode.PLATFORM_ORGANIZATION_GRANTS_MANAGE);
        var permission = parseOrganizationPermission(permissionCode);
        if (organizations.findById(organizationId).isEmpty() || !users.exists(userId)) {
            throw new AdministrativeTargetNotFoundException();
        }
        var grant = new AdministrativeGrant(
                userId, AdministrativeScope.organization(organizationId), permission);
        if (granting) {
            grants.grant(actor, grant, correlationId);
        } else {
            grants.revoke(actor, grant, correlationId);
        }
    }

    @Override
    public List<OrganizationTenantSummary> listTenants(UUID actor, UUID organizationId) {
        var required = new AdministrativeGrant(
                actor, AdministrativeScope.organization(organizationId),
                PermissionCode.ORGANIZATION_TENANTS_VIEW);
        if (authorization.authorize(required) != AuthorizationDecision.ALLOW) {
            throw new OrganizationUnavailableException();
        }
        var organization = organizations.findById(organizationId)
                .filter(candidate -> candidate.status() == OrganizationStatus.ACTIVE)
                .orElseThrow(OrganizationUnavailableException::new);
        return placements.findTenantIdsByOrganizationId(organization.id()).stream()
                .map(tenants::findById)
                .flatMap(java.util.Optional::stream)
                .map(tenant -> new OrganizationTenantSummary(
                        tenant.id(), tenant.name(), tenant.status()))
                .toList();
    }

    private void requirePlatform(UUID actor, PermissionCode permission) {
        var required = new AdministrativeGrant(
                Objects.requireNonNull(actor, "actor"), AdministrativeScope.platform(), permission);
        if (authorization.authorize(required) != AuthorizationDecision.ALLOW) {
            throw new AdministrationAccessDeniedException();
        }
    }

    private void requireTenant(UUID tenantId) {
        if (tenants.findById(Objects.requireNonNull(tenantId, "tenantId")).isEmpty()) {
            throw new AdministrativeTargetNotFoundException();
        }
    }

    private static PermissionCode parseOrganizationPermission(String raw) {
        try {
            var permission = PermissionCode.valueOf(raw);
            if (!permission.supportsAdministrativeScope(
                    io.github.piresrenan.orderhub.authorization.domain.model.AdministrativeScopeType.ORGANIZATION)) {
                throw new IllegalArgumentException("Permission is not organization-scoped");
            }
            return permission;
        } catch (NullPointerException | IllegalArgumentException exception) {
            throw new IllegalArgumentException("Invalid organization permission", exception);
        }
    }

    private void appendPlacement(UUID actor, UUID organizationId, UUID tenantId,
            UUID correlationId, OrganizationAdministrativeAuditAction action,
            OrganizationAdministrativeAuditOutcome outcome, UUID before, UUID after) {
        audit.append(new OrganizationAdministrativeAuditEvidence(
                auditIds.get(), actor, organizationId, tenantId, action, outcome,
                null, null, before, after, correlationId));
    }
}
