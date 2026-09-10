package io.github.piresrenan.orderhub.users.application.port.out;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ExternalIdentityLifecycleRepository {
    record Proof(UUID id, UUID user, UUID correlation, OffsetDateTime expiresAt) {}
    record Creation(Proof proof, boolean created) {}
    record Account(UUID id, String issuer, boolean active) {}
    record Link(UUID binding, boolean changed) {}
    Creation create(UUID user, UUID operation, UUID correlation, byte[] digest);
    Optional<Proof> consume(byte[] digest);
    Optional<Proof> cancel(UUID user, UUID proof);
    void lockUser(UUID user);
    List<Account> accounts(UUID user);
    Link link(UUID user, String issuer, String subject);
    boolean unlink(UUID user, UUID binding);
    void append(UUID user, UUID binding, UUID proof, String action, boolean changed, UUID correlation);
}
