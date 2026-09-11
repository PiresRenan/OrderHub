package io.github.piresrenan.orderhub.users.application.port.in;

import java.util.List;
import java.util.UUID;

/** Actor IDs come from authentication. New external facts come only from independent JWT verification. */
public interface ExternalIdentityLifecycleUseCase {
    /** Requires a usable authentication path and returns a new bounded proof only on initial issuance. */
    ExternalIdentityLinkIssuance issue(UUID trustedUserId, UUID operationId, UUID correlationId);
    /** Combines verified identity, proof consumption and exact-pair serialization without creating a replacement User. */
    UUID consume(String credential, String verifiedIssuer, String verifiedSubject);
    /** Retains historical ownership and rejects removal of the last currently trusted active authentication path. */
    boolean unlink(UUID trustedUserId, UUID bindingId, UUID correlationId);
    /** Cancels an owner-scoped pending proof with atomic attribution; repeated terminal requests are unchanged. */
    boolean cancel(UUID trustedUserId, UUID proofId, UUID correlationId);
    /** Returns the owner private active-binding projection without exposing provider subjects. */
    List<ExternalIdentityAccount> accounts(UUID trustedUserId);
}
