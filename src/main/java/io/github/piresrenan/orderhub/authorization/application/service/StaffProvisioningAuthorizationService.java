package io.github.piresrenan.orderhub.authorization.application.service;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import io.github.piresrenan.orderhub.authorization.application.port.in.provisioning.*;
import io.github.piresrenan.orderhub.authorization.application.port.out.*;
import io.github.piresrenan.orderhub.authorization.domain.constraint.AuthorizationConstraint;
import io.github.piresrenan.orderhub.authorization.domain.model.*;
import io.github.piresrenan.orderhub.authorization.domain.service.RoleDelegationPolicy;
import io.github.piresrenan.orderhub.authorization.domain.service.ScopedAuthorizationEvaluator;

/**
 * Authorizes normal Tenant Staff provisioning against stabilized owner state.
 * Role-defined envelopes bound delegation; actor-specific ALLOW overrides never
 * enlarge that bound. Effective permissions still pass the existing kernel and
 * every configured restrictive constraint. This service owns no independent commit.
 */
public final class StaffProvisioningAuthorizationService implements StaffProvisioningAuthorizationUseCase {
    private final RoleAssignmentRepository assignments;
    private final RoleDefinitionRepository definitions;
    private final UserPermissionOverrideRepository overrides;
    private final StaffProvisioningAuthorizationRepository persistence;
    private final List<AuthorizationConstraint> constraints;
    private final ScopedAuthorizationEvaluator evaluator = new ScopedAuthorizationEvaluator();
    private final RoleDelegationPolicy delegation = new RoleDelegationPolicy();

    /** Requires owner-local persistence and the same restrictive policy inputs as the Tenant kernel. */
    public StaffProvisioningAuthorizationService(RoleAssignmentRepository assignments,
            RoleDefinitionRepository definitions, UserPermissionOverrideRepository overrides,
            StaffProvisioningAuthorizationRepository persistence, List<AuthorizationConstraint> constraints) {
        this.assignments = Objects.requireNonNull(assignments, "assignments");
        this.definitions = Objects.requireNonNull(definitions, "definitions");
        this.overrides = Objects.requireNonNull(overrides, "overrides");
        this.persistence = Objects.requireNonNull(persistence, "persistence");
        this.constraints = List.copyOf(constraints);
    }

    /** Establishes manager eligibility before workforce resolves a sensitive target. */
    @Override
    public void requireManager(StaffProvisioningActor actor) {
        require(load(actor).effective().contains(PermissionCode.TENANT_MEMBERS_MANAGE));
    }

    /** Requires the full position and optional role to fit current delegation without clipping. */
    @Override
    public void requirePlacement(StaffProvisioningActor actor, StaffProvisioningTarget target, String initialRoleCode) {
        checkPlacement(actor, target, initialRoleCode, load(actor));
    }

    /** Applies RoleDelegationPolicy to the actual resolved target User before the atomic write. */
    @Override
    public void assign(StaffProvisioningActor actor, StaffProvisioningTarget target, UUID targetUserId,
            String roleCode, UUID intentId, UUID correlationId) {
        Objects.requireNonNull(targetUserId, "targetUserId");
        Objects.requireNonNull(roleCode, "roleCode");
        Objects.requireNonNull(intentId, "intentId");
        Objects.requireNonNull(correlationId, "correlationId");
        var state = load(actor);
        var role = checkPlacement(actor, target, roleCode, state);
        var scope = new TenantAuthorizationScope(actor.tenantId());
        var proposed = new RoleAssignment(targetUserId, AuthorizationPersona.STAFF, scope, roleCode);
        require(delegation.evaluate(actor.userId(), scope, actor.band(), state.effective(),
                state.delegation(), target.envelope(), proposed, role) == AuthorizationDecision.ALLOW);
        persistence.lock(targetUserId, actor.tenantId());
        var existing = assignments.findByUserIdAndScope(targetUserId, scope);
        var changed = !existing.contains(proposed);
        if (changed) { assignments.save(proposed); }
        persistence.append(actor.userId(), actor.tenantId(), targetUserId, roleCode, intentId, correlationId, changed);
    }

    /** Resolves all actor policy facts only after persistence has stabilized them. */
    private State load(StaffProvisioningActor actor) {
        Objects.requireNonNull(actor, "actor");
        persistence.lock(actor.userId(), actor.tenantId());
        var scope = new TenantAuthorizationScope(actor.tenantId());
        var grants = assignments.findByUserIdAndScope(actor.userId(), scope);
        var roles = new HashMap<String, RoleDefinition>();
        var delegable = EnumSet.noneOf(PermissionCode.class);
        for (var grant : grants) {
            if (!grant.appliesTo(actor.userId(), AuthorizationPersona.STAFF, scope)) {
                throw new AuthorizationPersistenceException("Provisioning actor state is inconsistent");
            }
            var role = definitions.findByCodeAndScope(grant.roleCode(), scope)
                    .orElseThrow(() -> new AuthorizationPersistenceException("Provisioning role state is inconsistent"));
            if (roles.putIfAbsent(role.code(), role) != null) {
                throw new AuthorizationPersistenceException("Provisioning role state is inconsistent");
            }
            delegable.addAll(role.permissionEnvelope().permissions());
        }
        var adjustments = overrides.findByUserIdAndScope(actor.userId(), scope);
        var effective = EnumSet.noneOf(PermissionCode.class);
        for (var permission : PermissionCode.values()) {
            if (permission.supports(AuthorizationPersona.STAFF)
                    && evaluator.evaluate(new TenantAuthorizationRequest(actor.userId(), AuthorizationPersona.STAFF,
                            scope, permission), grants, roles, actor.envelope(), adjustments, constraints) == AuthorizationDecision.ALLOW) {
                effective.add(permission);
            }
        }
        delegable.retainAll(actor.envelope().permissions());
        delegable.retainAll(effective);
        return new State(Set.copyOf(effective), PermissionEnvelope.of(delegable));
    }

    /** Checks pre-assignment ceilings without inventing an unknown future target User. */
    private RoleDefinition checkPlacement(StaffProvisioningActor actor, StaffProvisioningTarget target,
            String roleCode, State state) {
        Objects.requireNonNull(target, "target");
        require(state.effective().contains(PermissionCode.TENANT_MEMBERS_MANAGE));
        require(actor.band().isAtLeast(target.band()));
        require(state.delegation().containsAll(target.envelope().permissions()));
        if (roleCode == null) { return null; }
        require(state.effective().contains(PermissionCode.TENANT_ROLES_ASSIGN));
        var role = definitions.findByCodeAndScope(roleCode, new TenantAuthorizationScope(actor.tenantId()))
                .orElseThrow(StaffProvisioningAuthorizationDeniedException::new);
        require(role.mutability() != RoleMutability.SYSTEM_LOCKED);
        require(actor.band().isAtLeast(role.authorityBand()) && target.band().isAtLeast(role.authorityBand()));
        require(state.delegation().containsAll(role.permissions()) && target.envelope().containsAll(role.permissions()));
        if (role.mutability() == RoleMutability.TENANT_PROTECTED || role.authorityBand() == AuthorityBand.TENANT_GOVERNANCE) {
            require(state.effective().contains(PermissionCode.TENANT_PRIVILEGED_ROLES_ASSIGN));
        }
        return role;
    }

    /** Keeps every policy rejection independent of sensitive actor or target details. */
    private static void require(boolean allowed) {
        if (!allowed) { throw new StaffProvisioningAuthorizationDeniedException(); }
    }

    private record State(Set<PermissionCode> effective, PermissionEnvelope delegation) {}
}
