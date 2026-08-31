package io.github.piresrenan.orderhub.security.application.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.Test;

class TrustedTenantContextTest {

    @Test
    void retainsOnlyTrustedTenantIdentity() {
        // Why: downstream Orders code needs Tenant authority only after the
        // security boundary has established membership.
        // Covers: the minimum trusted context defined by ADR-0008.
        // Prevents: propagating authenticated User identity, JWT claims or
        // provider-specific authentication state into Orders unnecessarily.

        var tenantId = UUID.randomUUID();

        var context =
                new TrustedTenantContext(
                        tenantId);

        assertThat(context.tenantId())
                .isEqualTo(tenantId);
    }

    @Test
    void rejectsMissingTrustedTenantIdentity() {
        // Why: a trusted Tenant context without Tenant identity cannot represent
        // an authorization decision.
        // Covers: structural completeness of the trusted context.
        // Prevents: null Tenant authority reaching downstream application code.

        assertThatThrownBy(() ->
                new TrustedTenantContext(
                        null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
