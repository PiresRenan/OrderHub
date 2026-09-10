package io.github.piresrenan.orderhub.workforce.application.service;

import java.util.Objects;
import java.util.UUID;
import java.time.Clock;
import java.time.OffsetDateTime;
import io.github.piresrenan.orderhub.authorization.application.port.in.provisioning.ColdStartStaffAuthorizationUseCase;
import io.github.piresrenan.orderhub.authorization.domain.model.AuthorityBand;
import io.github.piresrenan.orderhub.tenants.application.port.in.operational.FindTenantOperationalStateQuery;
import io.github.piresrenan.orderhub.tenants.application.port.in.operational.FindTenantOperationalStateUseCase;
import io.github.piresrenan.orderhub.tenants.application.port.in.operational.TenantOperationalState;
import io.github.piresrenan.orderhub.workforce.application.model.*;
import io.github.piresrenan.orderhub.workforce.application.port.in.ColdStartStaffProvisioningUseCase;
import io.github.piresrenan.orderhub.workforce.application.port.in.IssueStaffProvisioningIntentUseCase;
import io.github.piresrenan.orderhub.workforce.application.port.out.*;

/** First-Staff setup; Platform identity never substitutes for a normal Tenant Staff actor. */
public final class ColdStartStaffProvisioningService implements ColdStartStaffProvisioningUseCase {
    private final ColdStartStaffAuthorizationUseCase authorization;
    private final ColdStartStaffRepository coldStart;
    private final StaffProvisioningFactsRepository facts;
    private final FindTenantOperationalStateUseCase tenants;
    private final IssueStaffProvisioningIntentUseCase primitive;
    private final StaffProvisioningEvidenceRepository evidence;
    private final WorkforceTransactionExecutor transaction;
    private final StaffProvisioningIntentRepository intents;
    private final Clock clock;

    public ColdStartStaffProvisioningService(ColdStartStaffAuthorizationUseCase authorization, ColdStartStaffRepository coldStart,
            StaffProvisioningFactsRepository facts, FindTenantOperationalStateUseCase tenants, IssueStaffProvisioningIntentUseCase primitive,
            StaffProvisioningEvidenceRepository evidence, WorkforceTransactionExecutor transaction,
            StaffProvisioningIntentRepository intents, Clock clock) {
        this.authorization = Objects.requireNonNull(authorization, "authorization");
        this.coldStart = Objects.requireNonNull(coldStart, "coldStart");
        this.facts = Objects.requireNonNull(facts, "facts");
        this.tenants = Objects.requireNonNull(tenants, "tenants");
        this.primitive = Objects.requireNonNull(primitive, "primitive");
        this.evidence = Objects.requireNonNull(evidence, "evidence");
        this.transaction = Objects.requireNonNull(transaction, "transaction");
        this.intents = Objects.requireNonNull(intents, "intents");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override public StaffProvisioningIssuance issue(UUID actorUserId, UUID tenantId, UUID operationId, UUID correlationId) {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(correlationId, "correlationId");
        return transaction.execute(() -> {
            requireEmpty(actorUserId, tenantId);
            var role = authorization.plan(actorUserId, tenantId);
            var placement = coldStart.prepare(tenantId, role.envelope());
            var result = primitive.issue(new IssueStaffProvisioningIntentCommand(tenantId, actorUserId, placement.departmentId(),
                    placement.positionId(), role.code(), operationId, correlationId));
            if (result instanceof StaffProvisioningIssuance.Issued issued) {
                evidence.append(new StaffProvisioningEvidence(tenantId, issued.intentId(), actorUserId, null, null,
                        StaffProvisioningEvidence.Action.COLD_START_ISSUED, correlationId));
            }
            return result;
        });
    }

    /** Platform cleanup ends when the Tenant establishes its own Staff authority. */
    @Override public boolean cancel(UUID actorUserId, UUID tenantId, UUID intentId, UUID correlationId) {
        Objects.requireNonNull(intentId, "intentId");
        Objects.requireNonNull(correlationId, "correlationId");
        return transaction.execute(() -> {
            authorization.requirePlatformManager(actorUserId);
            if (!evidence.isColdStart(tenantId, intentId)) { throw new StaffProvisioningUnavailableException(); }
            // Match consumption's intent-before-governance lock order. The
            // shared Platform grant lock is compatible with a competing consumer.
            var changed = intents.cancelPending(tenantId, intentId, OffsetDateTime.now(clock));
            requireEmpty(actorUserId, tenantId);
            if (changed) {
                evidence.append(new StaffProvisioningEvidence(tenantId, intentId, actorUserId, null, null,
                        StaffProvisioningEvidence.Action.CANCELLED, correlationId));
            }
            return changed;
        });
    }

    /** Called only for immutable cold-start issuance evidence, before Users is mutated. */
    public void authorize(ConsumedStaffProvisioningIntent intent) {
        requireEmpty(intent.issuedByUserId(), intent.tenantId());
        var role = authorization.plan(intent.issuedByUserId(), intent.tenantId());
        var target = facts.target(intent.tenantId(), intent.departmentId(), intent.positionId()).orElseThrow(StaffProvisioningUnavailableException::new);
        if (!role.code().equals(intent.initialRoleCode()) || target.band() != AuthorityBand.TENANT_GOVERNANCE
                || !target.envelope().permissions().equals(role.envelope().permissions())) {
            throw new StaffProvisioningUnavailableException();
        }
    }

    /** The caller already holds the empty-Tenant lock through materialization. */
    public void complete(ConsumedStaffProvisioningIntent intent, UUID userId, UUID staffId) {
        authorization.requirePlatformManager(intent.issuedByUserId());
        requireActiveTenant(intent.tenantId());
        authorization.assign(intent.issuedByUserId(), intent.tenantId(), userId, intent.intentId(), intent.correlationId());
        evidence.append(new StaffProvisioningEvidence(intent.tenantId(), intent.intentId(), intent.issuedByUserId(), userId, staffId,
                StaffProvisioningEvidence.Action.CONSUMED, intent.correlationId()));
    }

    private void requireEmpty(UUID actor, UUID tenant) {
        authorization.requirePlatformManager(actor);
        requireActiveTenant(tenant);
        coldStart.lockEmptyTenant(tenant);
    }

    private void requireActiveTenant(UUID tenantId) {
        if (tenants.find(new FindTenantOperationalStateQuery(tenantId)).orElse(null) != TenantOperationalState.ACTIVE) {
            throw new StaffProvisioningUnavailableException();
        }
    }
}
