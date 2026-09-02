package io.github.piresrenan.orderhub.workforce.application.service;

import io.github.piresrenan.orderhub.authorization.domain.model.PermissionEnvelope;
import io.github.piresrenan.orderhub.workforce.application.model.PrivilegedPositionChangeRequest;
import io.github.piresrenan.orderhub.workforce.domain.model.EffectiveWorkforceAuthority;
import io.github.piresrenan.orderhub.workforce.domain.model.StaffProfile;
import io.github.piresrenan.orderhub.workforce.domain.model.WorkforceMutationDecision;
import io.github.piresrenan.orderhub.workforce.domain.policy.PrivilegedWorkforceMutationPolicy;

/**
 * Composes privileged workforce mutation policy with the actor's current
 * organizational delegation ceiling.
 *
 * <p>
 * This service does not resolve authorization persistence. The upstream
 * privileged authorization outcome and delegation envelope are already-resolved
 * authorization facts. EffectiveWorkforceAuthority is likewise expected to have
 * been resolved for the exact actor Staff relationship.
 * </p>
 */
public final class PrivilegedWorkforceMutationAuthorizationService {

    private final PrivilegedWorkforceMutationPolicy mutationPolicy =
            new PrivilegedWorkforceMutationPolicy();

    public WorkforceMutationDecision authorize(
            PrivilegedPositionChangeRequest request,
            StaffProfile actor,
            StaffProfile target,
            EffectiveWorkforceAuthority actorAuthority,
            PermissionEnvelope actorDelegationEnvelope) {

        if (request == null) {
            throw new IllegalArgumentException(
                    "Privileged position change request is required");
        }

        if (actor == null) {
            throw new IllegalArgumentException(
                    "Actor Staff is required");
        }

        if (target == null) {
            throw new IllegalArgumentException(
                    "Target Staff is required");
        }

        if (actorAuthority == null) {
            throw new IllegalArgumentException(
                    "Actor workforce authority is required");
        }

        if (actorDelegationEnvelope == null) {
            throw new IllegalArgumentException(
                    "Actor delegation envelope is required");
        }

        var baseDecision =
                mutationPolicy.evaluate(
                        request.actorStaffId(),
                        request.targetStaffId(),
                        request.tenantId(),
                        actor,
                        target,
                        request.positionChange(),
                        request.actorPrivilegedAuthorizationAllowed());

        if (baseDecision
                != WorkforceMutationDecision.ALLOW) {

            return WorkforceMutationDecision.DENY;
        }

        var actorAuthorityBand =
                actorAuthority.authorityBand();

        if (actorAuthorityBand.isEmpty()) {

            return WorkforceMutationDecision.DENY;
        }

        var positionChange =
                request.positionChange();

        if (!actorAuthorityBand.get()
                .isAtLeast(
                        positionChange.afterAuthorityBand())) {

            return WorkforceMutationDecision.DENY;
        }

        if (!actorDelegationEnvelope.containsAll(
                positionChange
                        .afterPermissionEnvelope()
                        .permissions())) {

            return WorkforceMutationDecision.DENY;
        }

        return WorkforceMutationDecision.ALLOW;
    }
}
