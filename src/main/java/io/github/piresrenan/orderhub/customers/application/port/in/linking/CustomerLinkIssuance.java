package io.github.piresrenan.orderhub.customers.application.port.in.linking;

import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

public sealed interface CustomerLinkIssuance {
    /** Returned only once; a lost successful response requires cancellation and a new operation. */
    record Issued(UUID proofId, String credential, OffsetDateTime expiresAt) implements CustomerLinkIssuance {
        public Issued {
            Objects.requireNonNull(proofId, "proofId");
            Objects.requireNonNull(credential, "credential");
            Objects.requireNonNull(expiresAt, "expiresAt");
        }
        @Override public String toString() { return "CustomerLinkIssuance.Issued[credential=REDACTED]"; }
    }
    record Replay(UUID proofId) implements CustomerLinkIssuance {}
}
