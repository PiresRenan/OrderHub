package io.github.piresrenan.orderhub.security.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import io.github.piresrenan.orderhub.security.application.model.AuthenticatedUserPrincipal;
import io.github.piresrenan.orderhub.security.application.port.in.ResolveTrustedTenantContextQuery;
import io.github.piresrenan.orderhub.users.application.port.in.FindTenantMembershipQuery;
import io.github.piresrenan.orderhub.users.application.port.in.FindTenantMembershipUseCase;
import io.github.piresrenan.orderhub.users.domain.model.TenantMembership;

class ResolveTrustedTenantContextServiceTest {

    @Test
    void resolvesTrustedTenantWhenExactMembershipExists() {
        // Why: requested Tenant authority becomes trusted only after proving that
        // the authenticated internal User belongs to that exact Tenant.
        // Covers: successful membership lookup and projection into the minimal
        // TrustedTenantContext.
        // Prevents: trusting X-Tenant-Id merely because it was supplied by the
        // caller.

        var userId = UUID.randomUUID();
        var tenantId = UUID.randomUUID();

        FindTenantMembershipUseCase memberships =
                query -> Optional.of(
                        TenantMembership.create(
                                query.userId(),
                                query.tenantId()));

        var service =
                new ResolveTrustedTenantContextService(
                        memberships);

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
        // Prevents: looking up a membership for the wrong User, a fallback
        // Tenant or an incompletely scoped identity.

        var userId = UUID.randomUUID();
        var tenantId = UUID.randomUUID();

        var captured =
                new AtomicReference<FindTenantMembershipQuery>();

        FindTenantMembershipUseCase memberships =
                query -> {
                    captured.set(query);

                    return Optional.of(
                            TenantMembership.create(
                                    query.userId(),
                                    query.tenantId()));
                };

        var service =
                new ResolveTrustedTenantContextService(
                        memberships);

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
        // Covers: missing exact membership as an application-level access
        // resolution failure.
        // Prevents: authenticated callers crossing Tenant boundaries by changing
        // only the requested Tenant selector.

        FindTenantMembershipUseCase memberships =
                query -> Optional.empty();

        var service =
                new ResolveTrustedTenantContextService(
                        memberships);

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

        FindTenantMembershipUseCase memberships =
                query -> {
                    if (query.userId().equals(userId)
                            && query.tenantId().equals(authorizedTenantId)) {

                        return Optional.of(
                                TenantMembership.create(
                                        userId,
                                        authorizedTenantId));
                    }

                    return Optional.empty();
                };

        var service =
                new ResolveTrustedTenantContextService(
                        memberships);

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
                        null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
