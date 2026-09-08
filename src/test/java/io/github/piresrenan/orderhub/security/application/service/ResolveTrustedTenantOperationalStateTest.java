package io.github.piresrenan.orderhub.security.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

import io.github.piresrenan.orderhub.security.application.model.AuthenticatedUserPrincipal;
import io.github.piresrenan.orderhub.security.application.port.in.ResolveTrustedTenantContextQuery;
import io.github.piresrenan.orderhub.tenants.application.port.in.operational.FindTenantOperationalStateUseCase;
import io.github.piresrenan.orderhub.tenants.application.port.in.operational.TenantOperationalState;
import io.github.piresrenan.orderhub.tenants.application.port.in.operational.TenantOperationalStateUnavailableException;
import io.github.piresrenan.orderhub.users.application.port.in.IsTenantMembershipOperationallyActiveUseCase;

class ResolveTrustedTenantOperationalStateTest {

    @Test
    void trustsExactMembershipOnlyWhenTenantIsActive() {

        var userId =
                UUID.randomUUID();

        var tenantId =
                UUID.randomUUID();

        IsTenantMembershipOperationallyActiveUseCase memberships =
                query ->
                        true;

        FindTenantOperationalStateUseCase tenantStates =
                query ->
                        Optional.of(
                                TenantOperationalState.ACTIVE);

        var service =
                new ResolveTrustedTenantContextService(
                        memberships,
                        tenantStates);

        var result =
                service.resolve(
                        query(
                                userId,
                                tenantId));

        assertThat(result)
                .isPresent();

        assertThat(
                result.orElseThrow().tenantId())
                .isEqualTo(
                        tenantId);
    }

    @Test
    void deniesTrustedContextWhenTenantIsSuspended() {

        var userId =
                UUID.randomUUID();

        var tenantId =
                UUID.randomUUID();

        IsTenantMembershipOperationallyActiveUseCase memberships =
                query ->
                        true;

        FindTenantOperationalStateUseCase tenantStates =
                query ->
                        Optional.of(
                                TenantOperationalState.SUSPENDED);

        var service =
                new ResolveTrustedTenantContextService(
                        memberships,
                        tenantStates);

        assertThat(
                service.resolve(
                        query(
                                userId,
                                tenantId)))
                .isEmpty();
    }

    @Test
    void deniesTrustedContextWhenTenantNoLongerExists() {

        IsTenantMembershipOperationallyActiveUseCase memberships =
                query ->
                        true;

        FindTenantOperationalStateUseCase tenantStates =
                query ->
                        Optional.empty();

        var service =
                new ResolveTrustedTenantContextService(
                        memberships,
                        tenantStates);

        assertThat(
                service.resolve(
                        query(
                                UUID.randomUUID(),
                                UUID.randomUUID())))
                .isEmpty();
    }

    @Test
    void doesNotProbeTenantStateWhenMembershipIsAbsent() {

        var operationalLookupAttempted =
                new AtomicBoolean();

        IsTenantMembershipOperationallyActiveUseCase memberships =
                query ->
                        false;

        FindTenantOperationalStateUseCase tenantStates =
                query -> {
                    operationalLookupAttempted.set(
                            true);

                    return Optional.of(
                            TenantOperationalState.ACTIVE);
                };

        var service =
                new ResolveTrustedTenantContextService(
                        memberships,
                        tenantStates);

        assertThat(
                service.resolve(
                        query(
                                UUID.randomUUID(),
                                UUID.randomUUID())))
                .isEmpty();

        assertThat(operationalLookupAttempted)
                .isFalse();
    }

    @Test
    void propagatesTechnicalTenantStateFailureInsteadOfCreatingPolicyDenial() {

        var technicalFailure =
                new TenantOperationalStateUnavailableException(
                        new IllegalStateException(
                                "synthetic-internal-detail"));

        IsTenantMembershipOperationallyActiveUseCase memberships =
                query ->
                        true;

        FindTenantOperationalStateUseCase tenantStates =
                query -> {
                    throw technicalFailure;
                };

        var service =
                new ResolveTrustedTenantContextService(
                        memberships,
                        tenantStates);

        assertThatThrownBy(() ->
                service.resolve(
                        query(
                                UUID.randomUUID(),
                                UUID.randomUUID())))
                .isSameAs(
                        technicalFailure);
    }

    @Test
    void rejectsMissingTenantOperationalStateBoundary() {

        IsTenantMembershipOperationallyActiveUseCase memberships =
                query ->
                        false;

        assertThatThrownBy(() ->
                new ResolveTrustedTenantContextService(
                        memberships,
                        null))
                .isInstanceOf(
                        IllegalArgumentException.class)
                .hasMessage(
                        "Tenant operational-state boundary is required");
    }

    private static ResolveTrustedTenantContextQuery query(
            UUID userId,
            UUID tenantId) {

        return new ResolveTrustedTenantContextQuery(
                new AuthenticatedUserPrincipal(
                        userId),
                tenantId);
    }
}
