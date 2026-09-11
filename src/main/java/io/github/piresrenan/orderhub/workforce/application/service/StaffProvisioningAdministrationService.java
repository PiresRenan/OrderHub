package io.github.piresrenan.orderhub.workforce.application.service;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;
import io.github.piresrenan.orderhub.authorization.application.port.in.provisioning.StaffProvisioningAuthorizationUseCase;
import io.github.piresrenan.orderhub.tenants.application.port.in.operational.FindTenantOperationalStateUseCase;
import io.github.piresrenan.orderhub.users.application.port.in.IsTenantMembershipOperationallyActiveUseCase;
import io.github.piresrenan.orderhub.workforce.application.model.IssueStaffProvisioningIntentCommand;
import io.github.piresrenan.orderhub.workforce.application.model.StaffProvisioningEvidence;
import io.github.piresrenan.orderhub.workforce.application.model.StaffProvisioningIssuance;
import io.github.piresrenan.orderhub.workforce.application.port.in.IssueStaffProvisioningIntentUseCase;
import io.github.piresrenan.orderhub.workforce.application.port.in.ManageStaffProvisioningUseCase;
import io.github.piresrenan.orderhub.workforce.application.port.in.authorization.AuthorizeStaffTenantActionUseCase;
import io.github.piresrenan.orderhub.authorization.domain.model.AuthorizationDecision;
import io.github.piresrenan.orderhub.authorization.domain.model.PermissionCode;
import io.github.piresrenan.orderhub.authorization.application.port.in.provisioning.StaffProvisioningAuthorizationDeniedException;
import io.github.piresrenan.orderhub.workforce.application.port.out.StaffProvisioningEvidenceRepository;
import io.github.piresrenan.orderhub.workforce.application.port.out.StaffProvisioningFactsRepository;
import io.github.piresrenan.orderhub.workforce.application.port.out.StaffProvisioningIntentRepository;
import io.github.piresrenan.orderhub.workforce.application.port.out.WorkforceTransactionExecutor;

/** Normal Tenant Staff management; the issuance primitive is never caller authority. */
public final class StaffProvisioningAdministrationService implements ManageStaffProvisioningUseCase {
    private final StaffProvisioningIssuerEligibility eligibility;
    private final StaffProvisioningFactsRepository facts;
    private final StaffProvisioningAuthorizationUseCase authorization;
    private final StaffProvisioningEvidenceRepository evidence;
    private final IssueStaffProvisioningIntentUseCase primitive;
    private final StaffProvisioningIntentRepository intents;
    private final WorkforceTransactionExecutor transaction;
    private final Clock clock;
    private final AuthorizeStaffTenantActionUseCase readAuthority;

    /** Requires the supplied owner contracts; construction performs no lifecycle mutation or independent commit. */
    public StaffProvisioningAdministrationService(StaffProvisioningFactsRepository facts,
            StaffProvisioningAuthorizationUseCase authorization, IsTenantMembershipOperationallyActiveUseCase memberships,
            FindTenantOperationalStateUseCase tenants, StaffProvisioningEvidenceRepository evidence,
            IssueStaffProvisioningIntentUseCase primitive, StaffProvisioningIntentRepository intents,
            WorkforceTransactionExecutor transaction, Clock clock, AuthorizeStaffTenantActionUseCase readAuthority) {
        this.eligibility = new StaffProvisioningIssuerEligibility(facts, authorization, memberships, tenants);
        this.facts = Objects.requireNonNull(facts, "facts");
        this.authorization = Objects.requireNonNull(authorization, "authorization");
        this.evidence = Objects.requireNonNull(evidence, "evidence");
        this.primitive = Objects.requireNonNull(primitive, "primitive");
        this.intents = Objects.requireNonNull(intents, "intents");
        this.transaction = Objects.requireNonNull(transaction, "transaction");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.readAuthority = Objects.requireNonNull(readAuthority, "readAuthority");
    }

    /** Caller identity must originate in trusted context; target selectors convey no authority. */
    @Override
    public StaffProvisioningIssuance issue(IssueStaffProvisioningIntentCommand command) {
        Objects.requireNonNull(command, "command");
        return transaction.execute(() -> {
            var actor = eligibility.require(command.issuedByUserId(), command.tenantId());
            var target = facts.target(command.tenantId(), command.departmentId(), command.positionId())
                    .orElseThrow(StaffProvisioningUnavailableException::new);
            authorization.requirePlacement(actor, target, command.initialRoleCode());
            var result = Objects.requireNonNull(primitive.issue(command), "issuance result");
            if (result instanceof StaffProvisioningIssuance.Issued issued) {
                evidence.append(new StaffProvisioningEvidence(command.tenantId(), issued.intentId(), command.issuedByUserId(),
                        null, null, StaffProvisioningEvidence.Action.ISSUED, command.correlationId()));
            }
            return result;
        });
    }

    /** A manager can revoke a Tenant intent; only an applied terminal change adds evidence. */
    @Override
    public boolean cancel(UUID actorUserId, UUID tenantId, UUID intentId, UUID correlationId) {
        Objects.requireNonNull(actorUserId, "actorUserId");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(intentId, "intentId");
        Objects.requireNonNull(correlationId, "correlationId");
        // The existing read-only authority boundary protects sensitive intent
        // access without holding Staff locks while waiting for the intent row.
        // The mutation transaction revalidates locked authority after acquiring
        // the intent, in the same order as consumption. Failure rolls it back.
        eligibility.requireOperational(actorUserId, tenantId);
        if (readAuthority.authorize(actorUserId, tenantId, PermissionCode.TENANT_MEMBERS_MANAGE) != AuthorizationDecision.ALLOW) {
            throw new StaffProvisioningAuthorizationDeniedException();
        }
        return transaction.execute(() -> {
            var changed = intents.cancelPending(tenantId, intentId, OffsetDateTime.now(clock));
            eligibility.require(actorUserId, tenantId);
            if (changed) {
                evidence.append(new StaffProvisioningEvidence(tenantId, intentId, actorUserId,
                        null, null, StaffProvisioningEvidence.Action.CANCELLED, correlationId));
            }
            return changed;
        });
    }
}
