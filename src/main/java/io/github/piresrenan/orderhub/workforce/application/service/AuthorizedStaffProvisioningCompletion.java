package io.github.piresrenan.orderhub.workforce.application.service;

import java.util.Objects;
import java.util.UUID;
import io.github.piresrenan.orderhub.authorization.application.port.in.provisioning.StaffProvisioningActor;
import io.github.piresrenan.orderhub.authorization.application.port.in.provisioning.StaffProvisioningAuthorizationUseCase;
import io.github.piresrenan.orderhub.authorization.application.port.in.provisioning.StaffProvisioningTarget;
import io.github.piresrenan.orderhub.tenants.application.port.in.operational.FindTenantOperationalStateUseCase;
import io.github.piresrenan.orderhub.users.application.port.in.IsTenantMembershipOperationallyActiveUseCase;
import io.github.piresrenan.orderhub.workforce.application.model.ConsumedStaffProvisioningIntent;
import io.github.piresrenan.orderhub.workforce.application.model.StaffProvisioningEvidence;
import io.github.piresrenan.orderhub.workforce.application.port.out.StaffProvisioningCompletion;
import io.github.piresrenan.orderhub.workforce.application.port.out.StaffProvisioningEvidenceRepository;
import io.github.piresrenan.orderhub.workforce.application.port.out.StaffProvisioningFactsRepository;

/** Revalidates normal Tenant Staff authority and finishes owner-local atomic attribution. */
public final class AuthorizedStaffProvisioningCompletion implements StaffProvisioningCompletion {
    private final StaffProvisioningFactsRepository facts;
    private final StaffProvisioningAuthorizationUseCase authorization;
    private final StaffProvisioningIssuerEligibility eligibility;
    private final StaffProvisioningEvidenceRepository evidence;

    /** Requires concrete owner capabilities; no Platform fallback is inferred for normal intents. */
    public AuthorizedStaffProvisioningCompletion(StaffProvisioningFactsRepository facts,
            StaffProvisioningAuthorizationUseCase authorization, IsTenantMembershipOperationallyActiveUseCase memberships,
            FindTenantOperationalStateUseCase tenants, StaffProvisioningEvidenceRepository evidence) {
        this.facts = Objects.requireNonNull(facts, "facts");
        this.authorization = Objects.requireNonNull(authorization, "authorization");
        this.eligibility = new StaffProvisioningIssuerEligibility(facts, authorization, memberships, tenants);
        this.evidence = Objects.requireNonNull(evidence, "evidence");
    }

    /** Authorizes the issuer before target-sensitive lookup or speculative User creation. */
    @Override
    public void authorize(ConsumedStaffProvisioningIntent intent) {
        var actor = currentActor(intent);
        authorization.requirePlacement(actor, target(intent), intent.initialRoleCode());
    }

    /** Assigns only through Authorization, then writes the exact successfully materialized identity. */
    @Override
    public void complete(ConsumedStaffProvisioningIntent intent, UUID userId, UUID staffId) {
        var actor = currentActor(intent);
        var target = target(intent);
        if (intent.initialRoleCode() == null) {
            authorization.requirePlacement(actor, target, null);
        } else {
            authorization.assign(actor, target, userId, intent.initialRoleCode(), intent.intentId(), intent.correlationId());
        }
        evidence.append(new StaffProvisioningEvidence(intent.tenantId(), intent.intentId(), intent.issuedByUserId(),
                userId, staffId, StaffProvisioningEvidence.Action.CONSUMED, intent.correlationId()));
    }

    /** Checks current owner predicates; historical intent issuance never substitutes for current authority. */
    private StaffProvisioningActor currentActor(ConsumedStaffProvisioningIntent intent) {
        Objects.requireNonNull(intent, "intent");
        return eligibility.require(intent.issuedByUserId(), intent.tenantId());
    }

    /** Resolves only a same-Tenant target after the caller has passed manager authorization. */
    private StaffProvisioningTarget target(ConsumedStaffProvisioningIntent intent) {
        return facts.target(intent.tenantId(), intent.departmentId(), intent.positionId())
                .orElseThrow(StaffProvisioningUnavailableException::new);
    }
}
