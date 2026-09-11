package io.github.piresrenan.orderhub.users.application.port.in;

import java.time.OffsetDateTime;
import java.util.UUID;

public sealed interface ExternalIdentityLinkIssuance {
    record Issued(UUID proofId, String credential, OffsetDateTime expiresAt) implements ExternalIdentityLinkIssuance {
        /** Redacts credential or provider identity data from incidental textual logging. */
        @Override public String toString() { return "ExternalIdentityLinkIssuance.Issued[credential=REDACTED]"; }
    }
    record Replay(UUID proofId) implements ExternalIdentityLinkIssuance {}
}
