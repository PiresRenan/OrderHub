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
    /** Creates one User-scoped proof or returns its original identity on replay, never its secret. */
    Creation create(UUID user, UUID operation, UUID correlation, byte[] digest);
    /** Atomically selects and spends an eligible proof, preserving unavailable-state equivalence. */
    Optional<Proof> consume(byte[] digest);
    /** Changes only the authenticated owner proof while it remains pending. */
    Optional<Proof> cancel(UUID user, UUID proof);
    /** Serializes link/unlink on the stable User while remaining compatible with binding foreign-key locks. */
    void lockUser(UUID user);
    /** Reads only the selected User bindings for owner-local lifecycle decisions. */
    List<Account> accounts(UUID user);
    /** Preserves exact-pair ownership; only the same owner may reactivate a revoked binding. */
    Link link(UUID user, String issuer, String subject);
    /** Revokes usability while retaining the binding and its immutable owner history. */
    boolean unlink(UUID user, UUID binding);
    /** Persists bounded owner evidence as a required part of the current lifecycle transaction. */
    void append(UUID user, UUID binding, UUID proof, String action, boolean changed, UUID correlation);
}
