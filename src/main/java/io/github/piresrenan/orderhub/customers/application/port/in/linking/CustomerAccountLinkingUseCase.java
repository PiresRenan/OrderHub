package io.github.piresrenan.orderhub.customers.application.port.in.linking;

import java.util.UUID;

/** Internal actor IDs must originate from authentication, never request payloads. */
public interface CustomerAccountLinkingUseCase {
    /** Requires current management authority before selecting the Customer and issuing a bounded proof. */
    CustomerLinkIssuance issue(UUID actorUserId, UUID tenantId, UUID customerId, UUID operationId, UUID correlationId);
    /** The proof selects the Customer. No caller-provided Customer ID is accepted. */
    UUID consume(UUID trustedUserId, UUID tenantId, String credential);
    /** Revalidates the manager after any proof-lock wait before retaining cancellation evidence. */
    boolean cancel(UUID actorUserId, UUID tenantId, UUID proofId, UUID correlationId);
}
