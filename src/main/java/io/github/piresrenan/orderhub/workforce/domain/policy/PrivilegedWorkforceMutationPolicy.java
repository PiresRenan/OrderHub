package io.github.piresrenan.orderhub.workforce.domain.policy;

import java.util.UUID;

import io.github.piresrenan.orderhub.workforce.domain.model.PositionChange;
import io.github.piresrenan.orderhub.workforce.domain.model.StaffProfile;
import io.github.piresrenan.orderhub.workforce.domain.model.WorkforceMutationDecision;

/**
 * Fail-closed policy for one privileged Staff position mutation.
 *
 * <p>
 * Authorization resolution and mutation persistence remain separate concerns.
 * This policy consumes only domain facts, opaque request identities and an
 * explicit already-resolved privileged authorization outcome.
 * </p>
 */
public final class PrivilegedWorkforceMutationPolicy {

    public WorkforceMutationDecision evaluate(
            UUID requestActorStaffId,
            UUID requestTargetStaffId,
            UUID requestTenantId,
            StaffProfile actor,
            StaffProfile target,
            PositionChange positionChange,
            boolean actorPrivilegedAuthorizationAllowed) {

        if (requestActorStaffId == null) {
            throw new IllegalArgumentException(
                    "Request actor Staff ID is required");
        }

        if (requestTargetStaffId == null) {
            throw new IllegalArgumentException(
                    "Request target Staff ID is required");
        }

        if (requestTenantId == null) {
            throw new IllegalArgumentException(
                    "Request Tenant ID is required");
        }

        if (actor == null) {
            throw new IllegalArgumentException(
                    "Actor Staff is required");
        }

        if (target == null) {
            throw new IllegalArgumentException(
                    "Target Staff is required");
        }

        if (positionChange == null) {
            throw new IllegalArgumentException(
                    "Position change is required");
        }

        if (!requestActorStaffId.equals(
                actor.staffId())
                || !requestTargetStaffId.equals(
                        target.staffId())) {

            return WorkforceMutationDecision.DENY;
        }

        if (!requestTenantId.equals(
                actor.tenantId())
                || !requestTenantId.equals(
                        target.tenantId())
                || !requestTenantId.equals(
                        positionChange.tenantId())) {

            return WorkforceMutationDecision.DENY;
        }

        if (!requestTargetStaffId.equals(
                positionChange.staffId())) {

            return WorkforceMutationDecision.DENY;
        }

        if (!actor.isActive()
                || !target.isActive()) {

            return WorkforceMutationDecision.DENY;
        }

        if (!actorPrivilegedAuthorizationAllowed) {

            return WorkforceMutationDecision.DENY;
        }

        /*
         * Privileged organizational-authority mutation is never self-directed.
         *
         * This is intentionally stronger than checking PROMOTION alone:
         * a lateral JobPosition change can enlarge the PermissionEnvelope
         * without changing AuthorityBand and would otherwise provide a
         * self-escalation path.
         */
        if (actor.staffId()
                .equals(target.staffId())) {

            return WorkforceMutationDecision.DENY;
        }

        return WorkforceMutationDecision.ALLOW;
    }
}
