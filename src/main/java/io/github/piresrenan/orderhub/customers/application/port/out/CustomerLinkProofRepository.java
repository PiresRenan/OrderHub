package io.github.piresrenan.orderhub.customers.application.port.out;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

/** All mutations must participate in the caller's physical transaction. */
public interface CustomerLinkProofRepository {
    record Proof(UUID proofId, UUID tenantId, UUID customerId, UUID issuerUserId, UUID correlationId, OffsetDateTime expiresAt) {}
    record Creation(Proof proof, boolean created) {}
    Creation create(UUID actor, UUID tenant, UUID customer, UUID operation, UUID correlation, byte[] digest);
    Optional<Proof> consume(UUID tenant, byte[] digest);
    Optional<Proof> cancel(UUID tenant, UUID proof);
    void bind(Proof proof, UUID user);
    void append(Proof proof, UUID actor, UUID subject, String action, UUID correlation);
}
