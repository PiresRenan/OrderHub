package io.github.piresrenan.orderhub.security.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import io.github.piresrenan.orderhub.security.application.model.AuthenticatedUserPrincipal;
import io.github.piresrenan.orderhub.security.application.port.in.ResolveTrustedTenantContextQuery;
import io.github.piresrenan.orderhub.tenants.application.port.in.operational.TenantOperationalState;
import io.github.piresrenan.orderhub.users.application.port.in.IsTenantMembershipOperationallyActiveQuery;
import io.github.piresrenan.orderhub.users.application.port.in.IsTenantMembershipOperationallyActiveUseCase;

class ResolveTrustedTenantContextServiceTest {

    @Test
    void resolvesTrustedTenantWhenExactMembershipExists() {
        // Why: requested Tenant authority becomes trusted only after proving that
        // the authenticated internal User belongs to that exact Tenant.
        // Covers: successful membership verification and projection into the
        // minimal TrustedTenantContext.
        // Prevents: trusting X-Tenant-Id merely because it was supplied by the
        // caller.

        var userId = UUID.randomUUID();
        var tenantId = UUID.randomUUID();

        IsTenantMembershipOperationallyActiveUseCase memberships =
                query -> true;

        var service =
                new ResolveTrustedTenantContextService(
                        memberships,
                        query -> Optional.of(
                                TenantOperationalState.ACTIVE));

        var result =
                service.resolve(
                        new ResolveTrustedTenantContextQuery(
                                new AuthenticatedUserPrincipal(
                                        userId),
                                tenantId));

        assertThat(result)
                .isPresent();

        assertThat(result.orElseThrow().tenantId())
                .isEqualTo(tenantId);
    }

    @Test
    void delegatesExactAuthenticatedUserAndRequestedTenantPair() {
        // Why: access must be proven for the complete authenticated User/Tenant
        // pair rather than either identifier independently.
        // Covers: construction of the Users membership query from the internal
        // principal and requested Tenant selector.
        // Prevents: asking about a membership for the wrong User, a fallback
        // Tenant or an incompletely scoped identity.

        var userId = UUID.randomUUID();
        var tenantId = UUID.randomUUID();

        var captured =
                new AtomicReference<IsTenantMembershipOperationallyActiveQuery>();

        IsTenantMembershipOperationallyActiveUseCase memberships =
                query -> {
                    captured.set(query);

                    return true;
                };

        var service =
                new ResolveTrustedTenantContextService(
                        memberships,
                        query -> Optional.of(
                                TenantOperationalState.ACTIVE));

        service.resolve(
                new ResolveTrustedTenantContextQuery(
                        new AuthenticatedUserPrincipal(
                                userId),
                        tenantId));

        assertThat(captured.get())
                .isNotNull();

        assertThat(captured.get().userId())
                .isEqualTo(userId);

        assertThat(captured.get().tenantId())
                .isEqualTo(tenantId);
    }

    @Test
    void returnsEmptyWhenAuthenticatedUserHasNoRequestedMembership() {
        // Why: authentication alone must not grant authority inside an arbitrary
        // Tenant.
        // Covers: a negative Users membership answer as an application-level
        // access resolution failure.
        // Prevents: authenticated callers crossing Tenant boundaries by changing
        // only the requested Tenant selector.

        IsTenantMembershipOperationallyActiveUseCase memberships =
                query -> false;

        var service =
                new ResolveTrustedTenantContextService(
                        memberships,
                        query -> Optional.of(
                                TenantOperationalState.ACTIVE));

        var result =
                service.resolve(
                        new ResolveTrustedTenantContextQuery(
                                new AuthenticatedUserPrincipal(
                                        UUID.randomUUID()),
                                UUID.randomUUID()));

        assertThat(result)
                .isEmpty();
    }

    @Test
    void changingOnlyRequestedTenantCannotReuseMembershipFromAnotherTenant() {
        // Why: X-Tenant-Id remains attacker-controlled request input.
        // Covers: membership authorization being scoped to the requested Tenant
        // on every resolution.
        // Prevents: a valid membership in Tenant A being reused to enter Tenant B.

        var userId = UUID.randomUUID();
        var authorizedTenantId = UUID.randomUUID();
        var otherTenantId = UUID.randomUUID();

        IsTenantMembershipOperationallyActiveUseCase memberships =
                query -> query.userId().equals(userId)
                        && query.tenantId().equals(authorizedTenantId);

        var service =
                new ResolveTrustedTenantContextService(
                        memberships,
                        query -> Optional.of(
                                TenantOperationalState.ACTIVE));

        var authorized =
                service.resolve(
                        new ResolveTrustedTenantContextQuery(
                                new AuthenticatedUserPrincipal(
                                        userId),
                                authorizedTenantId));

        var unauthorized =
                service.resolve(
                        new ResolveTrustedTenantContextQuery(
                                new AuthenticatedUserPrincipal(
                                        userId),
                                otherTenantId));

        assertThat(authorized)
                .isPresent();

        assertThat(authorized.orElseThrow().tenantId())
                .isEqualTo(authorizedTenantId);

        assertThat(unauthorized)
                .isEmpty();
    }

    @Test
    void rejectsMissingMembershipBoundary() {
        // Why: the service cannot establish trusted Tenant authority without the
        // Users-owned membership source.
        // Covers: mandatory application dependency.
        // Prevents: accidental fail-open behavior when membership verification is
        // unavailable.

        assertThatThrownBy(() ->
                new ResolveTrustedTenantContextService(
                        null,
                        query -> Optional.of(
                                TenantOperationalState.ACTIVE)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
