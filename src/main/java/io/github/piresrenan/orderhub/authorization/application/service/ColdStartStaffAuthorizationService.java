package io.github.piresrenan.orderhub.authorization.application.service;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import io.github.piresrenan.orderhub.authorization.application.port.in.provisioning.ColdStartStaffAuthorizationUseCase;
import io.github.piresrenan.orderhub.authorization.application.port.in.provisioning.ColdStartStaffRole;
import io.github.piresrenan.orderhub.authorization.application.port.in.provisioning.StaffProvisioningAuthorizationDeniedException;
import io.github.piresrenan.orderhub.authorization.application.port.out.ColdStartStaffAuthorizationRepository;
import io.github.piresrenan.orderhub.authorization.application.port.out.RoleAssignmentRepository;
import io.github.piresrenan.orderhub.authorization.application.port.out.RoleDefinitionRepository;
import io.github.piresrenan.orderhub.authorization.application.port.out.StaffProvisioningAuthorizationRepository;
import io.github.piresrenan.orderhub.authorization.domain.model.*;

/** Explicit v1 initial governance policy, never an alternative to normal RoleDelegationPolicy. */
public final class ColdStartStaffAuthorizationService implements ColdStartStaffAuthorizationUseCase {
    private static final String CODE = "INITIAL_TENANT_GOVERNANCE_V1";
    // Explicit versioned vocabulary: future permissions are not silently added.
    private static final Set<PermissionCode> PERMISSIONS = Set.of(
            PermissionCode.TENANT_MEMBERS_VIEW, PermissionCode.TENANT_MEMBERS_MANAGE,
            PermissionCode.TENANT_ROLES_VIEW, PermissionCode.TENANT_ROLES_ASSIGN, PermissionCode.TENANT_PRIVILEGED_ROLES_ASSIGN,
            PermissionCode.CATALOG_VIEW, PermissionCode.CATALOG_MANAGE, PermissionCode.CATALOG_PRICE_MANAGE,
            PermissionCode.INVENTORY_VIEW, PermissionCode.INVENTORY_RECEIVE, PermissionCode.INVENTORY_ADJUST, PermissionCode.INVENTORY_POLICY_MANAGE,
            PermissionCode.ORDERS_VIEW, PermissionCode.ORDERS_CREATE, PermissionCode.ORDERS_MANAGE, PermissionCode.ORDERS_APPROVE,
            PermissionCode.AUDIT_VIEW);
    private final ColdStartStaffAuthorizationRepository coldStart;
    private final RoleDefinitionRepository definitions;
    private final RoleAssignmentRepository assignments;
    private final StaffProvisioningAuthorizationRepository evidence;

    /** Requires the supplied owner contracts; construction performs no lifecycle mutation or independent commit. */
    public ColdStartStaffAuthorizationService(ColdStartStaffAuthorizationRepository coldStart,
            RoleDefinitionRepository definitions, RoleAssignmentRepository assignments, StaffProvisioningAuthorizationRepository evidence) {
        this.coldStart = Objects.requireNonNull(coldStart, "coldStart");
        this.definitions = Objects.requireNonNull(definitions, "definitions");
        this.assignments = Objects.requireNonNull(assignments, "assignments");
        this.evidence = Objects.requireNonNull(evidence, "evidence");
    }

    /** Requires the current explicit Platform permission, without fabricating Staff authority. */
    @Override public void requirePlatformManager(UUID actorUserId) {
        Objects.requireNonNull(actorUserId, "actorUserId");
        require(coldStart.holdPlatformManagerGrant(actorUserId));
    }

    /** Validates any existing bootstrap role before returning the fixed v1 permission ceiling. */
    @Override public ColdStartStaffRole plan(UUID actorUserId, UUID tenantId) {
        requirePlatformManager(actorUserId);
        definitions.findByCodeAndScope(CODE, new TenantAuthorizationScope(tenantId)).ifPresent(this::validate);
        return new ColdStartStaffRole(CODE, PermissionEnvelope.of(PERMISSIONS));
    }

    /** Assigns the validated initial role and required attribution in the ambient transaction. */
    @Override public void assign(UUID actorUserId, UUID tenantId, UUID targetUserId, UUID intentId, UUID correlationId) {
        requirePlatformManager(actorUserId);
        coldStart.lockRoleCatalog();
        evidence.lock(targetUserId, tenantId);
        var scope = new TenantAuthorizationScope(tenantId);
        var existingRole = definitions.findByCodeAndScope(CODE, scope);
        if (existingRole.isPresent()) { validate(existingRole.get()); }
        else { coldStart.createGovernanceRole(tenantId, CODE, PERMISSIONS); }
        var assignment = new RoleAssignment(targetUserId, AuthorizationPersona.STAFF, scope, CODE);
        var changed = !assignments.findByUserIdAndScope(targetUserId, scope).contains(assignment);
        if (changed) { assignments.save(assignment); }
        // Role creation, assignment and attribution share this physical transaction.
        evidence.append(actorUserId, tenantId, targetUserId, CODE, intentId, correlationId, changed);
    }

    /** Rejects altered bootstrap role semantics instead of silently accepting a broader ceiling. */
    private void validate(RoleDefinition role) {
        require(role.code().equals(CODE) && role.persona() == AuthorizationPersona.STAFF
                && role.mutability() == RoleMutability.TENANT_CUSTOM && role.authorityBand() == AuthorityBand.TENANT_GOVERNANCE
                && role.permissions().equals(PERMISSIONS) && role.permissionEnvelope().permissions().equals(PERMISSIONS));
    }

    /** Keeps policy rejection bounded without exposing private authority or identity details. */
    private static void require(boolean condition) {
        if (!condition) { throw new StaffProvisioningAuthorizationDeniedException(); }
    }
}
