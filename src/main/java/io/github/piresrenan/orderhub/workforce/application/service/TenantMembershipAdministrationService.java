package io.github.piresrenan.orderhub.workforce.application.service;

import java.util.Objects;
import java.util.UUID;
import io.github.piresrenan.orderhub.authorization.application.port.in.current.AuthorizeCurrentTenantActionUseCase;
import io.github.piresrenan.orderhub.authorization.domain.model.*;
import io.github.piresrenan.orderhub.tenants.application.port.in.operational.*;
import io.github.piresrenan.orderhub.users.application.port.in.*;
import io.github.piresrenan.orderhub.workforce.application.port.in.ManageTenantMembershipUseCase;
import io.github.piresrenan.orderhub.workforce.application.port.in.TenantMembershipAdministrationUnavailableException;
import io.github.piresrenan.orderhub.workforce.application.port.out.TenantMembershipAdministrationFacts;
import io.github.piresrenan.orderhub.workforce.application.port.out.WorkforceTransactionExecutor;

/** The Tenant lock serializes mutual disable attempts; no business mutation runs in the read snapshot. */
public final class TenantMembershipAdministrationService implements ManageTenantMembershipUseCase {
    private final TenantMembershipAdministrationFacts facts;
    private final AuthorizeCurrentTenantActionUseCase authorization;
    private final IsTenantMembershipOperationallyActiveUseCase memberships;
    private final FindTenantOperationalStateUseCase tenants;
    private final TransitionTenantMembershipUseCase transitions;
    private final WorkforceTransactionExecutor transaction;
    public TenantMembershipAdministrationService(TenantMembershipAdministrationFacts facts, AuthorizeCurrentTenantActionUseCase authorization,
            IsTenantMembershipOperationallyActiveUseCase memberships, FindTenantOperationalStateUseCase tenants,
            TransitionTenantMembershipUseCase transitions, WorkforceTransactionExecutor transaction) {
        this.facts = Objects.requireNonNull(facts); this.authorization = Objects.requireNonNull(authorization);
        this.memberships = Objects.requireNonNull(memberships); this.tenants = Objects.requireNonNull(tenants);
        this.transitions = Objects.requireNonNull(transitions); this.transaction = Objects.requireNonNull(transaction);
    }
    @Override public boolean suspend(UUID actor, UUID tenant, UUID subject, UUID correlation) { return change(actor, tenant, subject, correlation, TransitionTenantMembershipUseCase.Action.SUSPEND); }
    @Override public boolean recover(UUID actor, UUID tenant, UUID subject, UUID correlation) { return change(actor, tenant, subject, correlation, TransitionTenantMembershipUseCase.Action.RECOVER); }
    @Override public boolean terminate(UUID actor, UUID tenant, UUID subject, UUID correlation) { return change(actor, tenant, subject, correlation, TransitionTenantMembershipUseCase.Action.TERMINATE); }

    private boolean change(UUID actor, UUID tenant, UUID subject, UUID correlation, TransitionTenantMembershipUseCase.Action action) {
        Objects.requireNonNull(actor); Objects.requireNonNull(tenant); Objects.requireNonNull(subject); Objects.requireNonNull(correlation);
        requireAuthority(actor, tenant, null);
        if (actor.equals(subject)) { throw new TenantMembershipAdministrationUnavailableException(); }
        return transaction.execute(() -> {
            facts.lockTenant(tenant);
            requireAuthority(actor, tenant, null);
            requireAuthority(actor, tenant, subject);
            try { return transitions.transition(actor, tenant, subject, action, correlation); }
            catch (TenantMembershipTransitionUnavailableException exception) { throw new TenantMembershipAdministrationUnavailableException(); }
        });
    }
    private void requireAuthority(UUID actor, UUID tenant, UUID subject) {
        // The isolated authorization snapshot cannot see this ambient transaction's own writes.
        // Require operational state here too, so an actor already disabled by the caller cannot act again.
        if (!memberships.isOperationallyActive(new IsTenantMembershipOperationallyActiveQuery(actor, tenant))
                || tenants.find(new FindTenantOperationalStateQuery(tenant)).orElse(null) != TenantOperationalState.ACTIVE) {
            throw new TenantMembershipAdministrationUnavailableException();
        }
        var request = new TenantAuthorizationRequest(actor, AuthorizationPersona.STAFF, new TenantAuthorizationScope(tenant), PermissionCode.TENANT_MEMBERS_MANAGE);
        var decision = authorization.authorizeCurrent(request, (user, scope) -> {
            if (!memberships.isOperationallyActive(new IsTenantMembershipOperationallyActiveQuery(user, scope))
                    || tenants.find(new FindTenantOperationalStateQuery(scope)).orElse(null) != TenantOperationalState.ACTIVE) { return PermissionEnvelope.none(); }
            return facts.envelope(user, scope, subject);
        });
        if (decision != AuthorizationDecision.ALLOW) { throw new TenantMembershipAdministrationUnavailableException(); }
    }
}
