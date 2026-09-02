package io.github.piresrenan.orderhub.authorization.domain.service;

import java.util.Set;
import java.util.UUID;

import io.github.piresrenan.orderhub.authorization.domain.model.AuthorityBand;
import io.github.piresrenan.orderhub.authorization.domain.model.AuthorizationDecision;
import io.github.piresrenan.orderhub.authorization.domain.model.PermissionCode;
import io.github.piresrenan.orderhub.authorization.domain.model.PermissionEnvelope;
import io.github.piresrenan.orderhub.authorization.domain.model.RoleAssignment;
import io.github.piresrenan.orderhub.authorization.domain.model.RoleDefinition;
import io.github.piresrenan.orderhub.authorization.domain.model.RoleMutability;
import io.github.piresrenan.orderhub.authorization.domain.model.TenantAuthorizationScope;

/**
 * Evaluates whether one trusted Staff actor may delegate one Tenant-scoped role
 * assignment.
 *
 * <p>
 * Effective business permission and delegation authority are deliberately
 * separate. Possessing a business permission does not itself allow the actor to
 * grant that permission to another account.
 * </p>
 */
public final class RoleDelegationPolicy {

    /**
     * Evaluates one proposed Tenant role assignment against the grantor and
     * target authorization ceilings.
     *
     * <p>
     * Self-assignment is treated as privileged administration. Protected Tenant
     * governance roles likewise require explicit privileged-role delegation.
     * SYSTEM_LOCKED roles never enter the ordinary Tenant delegation path.
     * </p>
     *
     * @param actorUserId authenticated internal grantor User
     * @param actorScope trusted Tenant scope in which the actor operates
     * @param actorAuthorityBand organizational delegation ceiling
     * @param actorEffectivePermissions currently effective actor permissions
     * @param actorDelegationEnvelope maximum permissions the actor may delegate
     * @param targetPermissionEnvelope maximum permissions the target may receive
     * @param proposedAssignment proposed target role assignment
     * @param proposedRole resolved role definition referenced by the assignment
     * @return ALLOW only when every independent delegation boundary accepts
     */
    public AuthorizationDecision evaluate(
            UUID actorUserId,
            TenantAuthorizationScope actorScope,
            AuthorityBand actorAuthorityBand,
            Set<PermissionCode> actorEffectivePermissions,
            PermissionEnvelope actorDelegationEnvelope,
            PermissionEnvelope targetPermissionEnvelope,
            RoleAssignment proposedAssignment,
            RoleDefinition proposedRole) {

        if (actorUserId == null) {
            throw new IllegalArgumentException(
                    "Delegating actor user id is required");
        }

        if (actorScope == null) {
            throw new IllegalArgumentException(
                    "Delegating actor scope is required");
        }

        if (actorAuthorityBand == null) {
            throw new IllegalArgumentException(
                    "Delegating actor authority band is required");
        }

        if (actorEffectivePermissions == null
                || actorEffectivePermissions.stream()
                        .anyMatch(permission ->
                                permission == null)) {

            throw new IllegalArgumentException(
                    "Delegating actor permissions are required");
        }

        if (actorDelegationEnvelope == null) {
            throw new IllegalArgumentException(
                    "Delegating actor envelope is required");
        }

        if (targetPermissionEnvelope == null) {
            throw new IllegalArgumentException(
                    "Target permission envelope is required");
        }

        if (proposedAssignment == null) {
            throw new IllegalArgumentException(
                    "Proposed role assignment is required");
        }

        if (proposedRole == null) {
            throw new IllegalArgumentException(
                    "Proposed role definition is required");
        }

        if (!actorScope.equals(
                proposedAssignment.scope())) {

            return AuthorizationDecision.DENY;
        }

        if (!proposedAssignment.roleCode()
                .equals(
                        proposedRole.code())
                || proposedAssignment.persona()
                        != proposedRole.persona()) {

            return AuthorizationDecision.DENY;
        }

        if (proposedRole.mutability()
                == RoleMutability.SYSTEM_LOCKED) {

            return AuthorizationDecision.DENY;
        }

        if (!actorEffectivePermissions.contains(
                PermissionCode.TENANT_ROLES_ASSIGN)) {

            return AuthorizationDecision.DENY;
        }

        var selfAssignment =
                actorUserId.equals(
                        proposedAssignment.userId());

        var privilegedRole =
                proposedRole.mutability()
                        == RoleMutability.TENANT_PROTECTED
                        || proposedRole.authorityBand()
                                == AuthorityBand.TENANT_GOVERNANCE;

        if ((selfAssignment || privilegedRole)
                && !actorEffectivePermissions.contains(
                        PermissionCode.TENANT_PRIVILEGED_ROLES_ASSIGN)) {

            return AuthorizationDecision.DENY;
        }

        if (!actorAuthorityBand.isAtLeast(
                proposedRole.authorityBand())) {

            return AuthorizationDecision.DENY;
        }

        if (!actorDelegationEnvelope.containsAll(
                proposedRole.permissions())) {

            return AuthorizationDecision.DENY;
        }

        if (!targetPermissionEnvelope.containsAll(
                proposedRole.permissions())) {

            return AuthorizationDecision.DENY;
        }

        return AuthorizationDecision.ALLOW;
    }
}