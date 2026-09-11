package io.github.piresrenan.orderhub.customers.application.port.out;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

/** All mutations must participate in the caller's physical transaction. */
public interface CustomerLinkProofRepository {
    record Proof(UUID proofId, UUID tenantId, UUID customerId, UUID issuerUserId, UUID correlationId, OffsetDateTime expiresAt) {}
    record Creation(Proof proof, boolean created) {}
    /** Creates one scoped proof or recognizes operation replay without recovering its secret. */
    Creation create(UUID actor, UUID tenant, UUID customer, UUID operation, UUID correlation, byte[] digest);
    /** Conditionally spends a pending unexpired proof and returns its frozen owner facts. */
    Optional<Proof> consume(UUID tenant, byte[] digest);
    /** Cancels only a pending proof in the selected Tenant; terminal repeats are unchanged. */
    Optional<Proof> cancel(UUID tenant, UUID proof);
    /** Establishes only the exact Customer/User tuple, preserving independent historical relationships. */
    void bind(Proof proof, UUID user);
    /** Makes owner attribution mandatory within the same transaction as the linking effect. */
    void append(Proof proof, UUID actor, UUID subject, String action, UUID correlation);
}
