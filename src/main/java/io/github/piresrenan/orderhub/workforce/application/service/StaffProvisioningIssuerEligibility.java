package io.github.piresrenan.orderhub.workforce.application.service;

import java.util.Objects;
import java.util.UUID;
import io.github.piresrenan.orderhub.authorization.application.port.in.provisioning.StaffProvisioningActor;
import io.github.piresrenan.orderhub.authorization.application.port.in.provisioning.StaffProvisioningAuthorizationUseCase;
import io.github.piresrenan.orderhub.tenants.application.port.in.operational.FindTenantOperationalStateQuery;
import io.github.piresrenan.orderhub.tenants.application.port.in.operational.FindTenantOperationalStateUseCase;
import io.github.piresrenan.orderhub.tenants.application.port.in.operational.TenantOperationalState;
import io.github.piresrenan.orderhub.users.application.port.in.IsTenantMembershipOperationallyActiveQuery;
import io.github.piresrenan.orderhub.users.application.port.in.IsTenantMembershipOperationallyActiveUseCase;
import io.github.piresrenan.orderhub.workforce.application.port.out.StaffProvisioningFactsRepository;

/** Common normal-Tenant eligibility for issuance, cancellation and consumption. */
final class StaffProvisioningIssuerEligibility {
    private final StaffProvisioningFactsRepository facts;
    private final StaffProvisioningAuthorizationUseCase authorization;
    private final IsTenantMembershipOperationallyActiveUseCase memberships;
    private final FindTenantOperationalStateUseCase tenants;

    StaffProvisioningIssuerEligibility(StaffProvisioningFactsRepository facts,
            StaffProvisioningAuthorizationUseCase authorization, IsTenantMembershipOperationallyActiveUseCase memberships,
            FindTenantOperationalStateUseCase tenants) {
        this.facts = Objects.requireNonNull(facts, "facts");
        this.authorization = Objects.requireNonNull(authorization, "authorization");
        this.memberships = Objects.requireNonNull(memberships, "memberships");
        this.tenants = Objects.requireNonNull(tenants, "tenants");
    }

    StaffProvisioningActor require(UUID userId, UUID tenantId) {
        requireOperational(userId, tenantId);
        var actor = facts.actor(userId, tenantId).orElseThrow(StaffProvisioningUnavailableException::new);
        authorization.requireManager(actor);
        return actor;
    }

    void requireOperational(UUID userId, UUID tenantId) {
        if (!memberships.isOperationallyActive(new IsTenantMembershipOperationallyActiveQuery(userId, tenantId))
                || tenants.find(new FindTenantOperationalStateQuery(tenantId)).orElse(null) != TenantOperationalState.ACTIVE) {
            throw new StaffProvisioningUnavailableException();
        }
    }
}
