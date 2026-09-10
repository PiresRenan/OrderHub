package io.github.piresrenan.orderhub.users.application.port.in;

import java.util.List;
import java.util.UUID;

/** Actor IDs come from authentication. New external facts come only from independent JWT verification. */
public interface ExternalIdentityLifecycleUseCase {
    ExternalIdentityLinkIssuance issue(UUID trustedUserId, UUID operationId, UUID correlationId);
    UUID consume(String credential, String verifiedIssuer, String verifiedSubject);
    boolean unlink(UUID trustedUserId, UUID bindingId, UUID correlationId);
    boolean cancel(UUID trustedUserId, UUID proofId, UUID correlationId);
    List<ExternalIdentityAccount> accounts(UUID trustedUserId);
}
