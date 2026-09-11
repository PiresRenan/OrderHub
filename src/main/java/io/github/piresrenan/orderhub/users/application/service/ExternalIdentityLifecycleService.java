package io.github.piresrenan.orderhub.users.application.service;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import io.github.piresrenan.orderhub.users.application.port.in.ExternalIdentityAccount;
import io.github.piresrenan.orderhub.users.application.port.in.ExternalIdentityLifecycleUnavailableException;
import io.github.piresrenan.orderhub.users.application.port.in.ExternalIdentityLifecycleUseCase;
import io.github.piresrenan.orderhub.users.application.port.in.ExternalIdentityLinkIssuance;
import io.github.piresrenan.orderhub.users.application.port.in.ResolveExternalIdentityQuery;
import io.github.piresrenan.orderhub.users.application.port.out.ExternalIdentityLifecycleRepository;
import io.github.piresrenan.orderhub.users.application.port.out.ExternalIdentityLifecycleTransaction;
import io.github.piresrenan.orderhub.users.application.port.out.ExternalIdentityUserProvisioningCoordinator;
import io.github.piresrenan.orderhub.users.application.port.out.TrustedExternalIdentityProviders;

/** Linking joins proof consumption, exact-pair serialization, stable User ownership and required evidence. */
public final class ExternalIdentityLifecycleService implements ExternalIdentityLifecycleUseCase {
    private final ExternalIdentityLifecycleRepository repository;
    private final ExternalIdentityLifecycleTransaction transaction;
    private final ExternalIdentityUserProvisioningCoordinator coordinator;
    private final TrustedExternalIdentityProviders providers;
    private final SecureRandom random = new SecureRandom();

    /** Requires the supplied owner contracts; construction performs no lifecycle mutation or independent commit. */
    public ExternalIdentityLifecycleService(ExternalIdentityLifecycleRepository repository, ExternalIdentityLifecycleTransaction transaction,
            ExternalIdentityUserProvisioningCoordinator coordinator, TrustedExternalIdentityProviders providers) {
        this.repository = Objects.requireNonNull(repository);
        this.transaction = Objects.requireNonNull(transaction);
        this.coordinator = Objects.requireNonNull(coordinator);
        this.providers = Objects.requireNonNull(providers);
    }

    /** Requires a usable authentication path and returns a new bounded proof only on initial issuance. */
    @Override public ExternalIdentityLinkIssuance issue(UUID user, UUID operation, UUID correlation) {
        Objects.requireNonNull(user); Objects.requireNonNull(operation); Objects.requireNonNull(correlation);
        return transaction.execute(() -> {
            require(repository.accounts(user).stream().anyMatch(account -> account.active() && providers.isTrusted(account.issuer())));
            var entropy = new byte[32]; random.nextBytes(entropy); var digest = hash(entropy);
            try {
                var result = repository.create(user, operation, correlation, digest);
                if (!result.created()) { return new ExternalIdentityLinkIssuance.Replay(result.proof().id()); }
                repository.append(user, null, result.proof().id(), "ISSUED", true, correlation);
                return new ExternalIdentityLinkIssuance.Issued(result.proof().id(), Base64.getUrlEncoder().withoutPadding().encodeToString(entropy), result.proof().expiresAt());
            } finally { Arrays.fill(entropy, (byte) 0); Arrays.fill(digest, (byte) 0); }
        });
    }

    /** Combines verified identity, proof consumption and exact-pair serialization without creating a replacement User. */
    @Override public UUID consume(String credential, String verifiedIssuer, String verifiedSubject) {
        var digest = decodeDigest(credential);
        try {
            var identity = new ResolveExternalIdentityQuery(verifiedIssuer, verifiedSubject);
            require(providers.isTrusted(identity.issuer()));
            return transaction.execute(() -> {
                var proof = repository.consume(digest).orElseThrow(ExternalIdentityLifecycleUnavailableException::new);
                return coordinator.executeSerialized(identity.issuer(), identity.subject(), () -> {
                    repository.lockUser(proof.user());
                    var linked = repository.link(proof.user(), identity.issuer(), identity.subject());
                    repository.append(proof.user(), linked.binding(), proof.id(), "LINKED", linked.changed(), proof.correlation());
                    return proof.user();
                });
            });
        } finally { Arrays.fill(digest, (byte) 0); }
    }

    /** Retains historical ownership and rejects removal of the last currently trusted active authentication path. */
    @Override public boolean unlink(UUID user, UUID binding, UUID correlation) {
        Objects.requireNonNull(user); Objects.requireNonNull(binding); Objects.requireNonNull(correlation);
        return transaction.execute(() -> {
            repository.lockUser(user);
            var accounts = repository.accounts(user);
            var selected = accounts.stream().filter(account -> account.id().equals(binding)).findFirst();
            if (selected.isEmpty() || !selected.get().active()) { return false; }
            // An active row at an issuer removed from server trust is not a usable alternative.
            require(accounts.stream().anyMatch(account -> !account.id().equals(binding) && account.active() && providers.isTrusted(account.issuer())));
            var changed = repository.unlink(user, binding);
            if (changed) { repository.append(user, binding, null, "UNLINKED", true, correlation); }
            return changed;
        });
    }

    /** Cancels an owner-scoped pending proof with atomic attribution; repeated terminal requests are unchanged. */
    @Override public boolean cancel(UUID user, UUID proofId, UUID correlation) {
        Objects.requireNonNull(user); Objects.requireNonNull(proofId); Objects.requireNonNull(correlation);
        return transaction.execute(() -> {
            var proof = repository.cancel(user, proofId);
            if (proof.isEmpty()) { return false; }
            repository.append(user, null, proofId, "CANCELLED", true, correlation);
            return true;
        });
    }

    /** Returns the owner private active-binding projection without exposing provider subjects. */
    @Override public List<ExternalIdentityAccount> accounts(UUID user) {
        Objects.requireNonNull(user);
        return transaction.execute(() -> repository.accounts(user).stream().filter(ExternalIdentityLifecycleRepository.Account::active)
                .map(account -> new ExternalIdentityAccount(account.id(), account.issuer())).toList());
    }

    /** Keeps policy rejection bounded without exposing private authority or identity details. */
    private static void require(boolean condition) { if (!condition) { throw new ExternalIdentityLifecycleUnavailableException(); } }
    /** Accepts only canonical 256-bit proof encoding and discards decoded secret material after hashing. */
    private static byte[] decodeDigest(String credential) {
        require(credential != null && credential.length() == 43);
        byte[] decoded;
        try { decoded = Base64.getUrlDecoder().decode(credential); }
        catch (IllegalArgumentException exception) { throw new ExternalIdentityLifecycleUnavailableException(); }
        try {
            require(decoded.length == 32 && Base64.getUrlEncoder().withoutPadding().encodeToString(decoded).equals(credential));
            return hash(decoded);
        } finally { Arrays.fill(decoded, (byte) 0); }
    }
    /** Produces the one-way credential digest without retaining raw proof material. */
    private static byte[] hash(byte[] value) {
        try { return MessageDigest.getInstance("SHA-256").digest(value); }
        catch (java.security.NoSuchAlgorithmException exception) { throw new IllegalStateException("SHA-256 is unavailable", exception); }
    }
}
