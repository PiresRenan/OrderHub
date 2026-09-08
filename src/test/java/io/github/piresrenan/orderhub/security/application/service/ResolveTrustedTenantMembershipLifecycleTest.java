package io.github.piresrenan.orderhub.security.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

import io.github.piresrenan.orderhub.security.application.model.AuthenticatedUserPrincipal;
import io.github.piresrenan.orderhub.security.application.port.in.ResolveTrustedTenantContextQuery;
import io.github.piresrenan.orderhub.tenants.application.port.in.operational.TenantOperationalState;
import io.github.piresrenan.orderhub.users.application.port.in.IsTenantMembershipOperationallyActiveUseCase;

class ResolveTrustedTenantMembershipLifecycleTest {

    @Test
    void nonOperationalMembershipCannotEstablishNewTrustedTenantContext() {
        // Why: a relationship that Users preserves but no longer considers
        // operational must stop producing new Tenant trust, even while the Tenant
        // itself remains ACTIVE.
        // Covers: a negative Users membership-eligibility answer denying trusted
        // Tenant context before any Tenant-state probing.
        // Prevents: Security re-deriving membership lifecycle policy, and a
        // suspended or terminated relationship retaining Tenant authority.

        var userId = UUID.randomUUID();
        var tenantId = UUID.randomUUID();
        var tenantStateWasQueried = new AtomicBoolean(false);

        IsTenantMembershipOperationallyActiveUseCase memberships =
                query -> false;

        var service =
                new ResolveTrustedTenantContextService(
                        memberships,
                        query -> {
                            tenantStateWasQueried.set(true);
                            return Optional.of(
                                    TenantOperationalState.ACTIVE);
                        });

        var result =
                service.resolve(
                        new ResolveTrustedTenantContextQuery(
                                new AuthenticatedUserPrincipal(
                                        userId),
                                tenantId));

        assertThat(result)
                .isEmpty();

        assertThat(tenantStateWasQueried)
                .as("non-operational membership must fail before Tenant-state probing")
                .isFalse();
    }
}
