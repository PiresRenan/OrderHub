package io.github.piresrenan.orderhub.security.application.port.in;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.github.piresrenan.orderhub.security.application.model.AuthenticatedUserPrincipal;

class ResolveTrustedTenantContextQueryTest {

    @Test
    void retainsAuthenticatedPrincipalAndRequestedTenant() {
        // Why: Tenant authority must be derived from an already authenticated
        // internal User together with the caller's requested Tenant selector.
        // Covers: the complete input required to prove Tenant membership.
        // Prevents: treating a raw User UUID or requested Tenant UUID as trusted
        // authority independently.

        var principal =
                new AuthenticatedUserPrincipal(
                        UUID.randomUUID());

        var requestedTenantId =
                UUID.randomUUID();

        var query =
                new ResolveTrustedTenantContextQuery(
                        principal,
                        requestedTenantId);

        assertThat(query.authenticatedPrincipal())
                .isSameAs(principal);

        assertThat(query.requestedTenantId())
                .isEqualTo(requestedTenantId);
    }

    @Test
    void rejectsMissingAuthenticatedPrincipal() {
        // Why: Tenant membership cannot establish request authority without an
        // authenticated internal User.
        // Covers: mandatory authenticated-principal input.
        // Prevents: unauthenticated callers entering Tenant authorization logic.

        assertThatThrownBy(() ->
                new ResolveTrustedTenantContextQuery(
                        null,
                        UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsMissingRequestedTenant() {
        // Why: membership verification requires one explicit Tenant selector.
        // Covers: mandatory requested Tenant identity.
        // Prevents: ambiguous or implicit Tenant selection inside the security
        // application service.

        assertThatThrownBy(() ->
                new ResolveTrustedTenantContextQuery(
                        new AuthenticatedUserPrincipal(
                                UUID.randomUUID()),
                        null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
