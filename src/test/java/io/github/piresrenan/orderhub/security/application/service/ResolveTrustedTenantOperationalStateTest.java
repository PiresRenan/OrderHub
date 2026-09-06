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
import io.github.piresrenan.orderhub.users.application.port.in.FindTenantMembershipUseCase;
import io.github.piresrenan.orderhub.users.domain.model.TenantMembership;

class ResolveTrustedTenantOperationalStateTest {

    @Test
    void trustsExactMembershipOnlyWhenTenantIsActive() {

        var userId =
                UUID.randomUUID();

        var tenantId =
                UUID.randomUUID();

        FindTenantMembershipUseCase memberships =
                query ->
                        Optional.of(
                                TenantMembership.create(
                                        query.userId(),
                                        query.tenantId()));

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

        FindTenantMembershipUseCase memberships =
                query ->
                        Optional.of(
                                TenantMembership.create(
                                        query.userId(),
                                        query.tenantId()));

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

        FindTenantMembershipUseCase memberships =
                query ->
                        Optional.of(
                                TenantMembership.create(
                                        query.userId(),
                                        query.tenantId()));

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

        FindTenantMembershipUseCase memberships =
                query ->
                        Optional.empty();

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

        FindTenantMembershipUseCase memberships =
                query ->
                        Optional.of(
                                TenantMembership.create(
                                        query.userId(),
                                        query.tenantId()));

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

        FindTenantMembershipUseCase memberships =
                query ->
                        Optional.empty();

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
