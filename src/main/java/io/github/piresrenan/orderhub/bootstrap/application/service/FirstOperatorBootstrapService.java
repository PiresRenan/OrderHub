package io.github.piresrenan.orderhub.bootstrap.application.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

import io.github.piresrenan.orderhub.authorization.application.port.in.bootstrap.FirstOperatorPlatformAuthorityUseCase;
import io.github.piresrenan.orderhub.bootstrap.application.port.in.BootstrapFirstOperatorUseCase;
import io.github.piresrenan.orderhub.bootstrap.application.port.in.FirstOperatorBootstrapOutcome;
import io.github.piresrenan.orderhub.bootstrap.application.port.in.FirstOperatorBootstrapRequest;
import io.github.piresrenan.orderhub.bootstrap.application.port.out.FirstOperatorBootstrapTransaction;
import io.github.piresrenan.orderhub.bootstrap.application.port.out.FirstOperatorCeremonyRepository;
import io.github.piresrenan.orderhub.bootstrap.application.port.out.FirstOperatorCeremonyState;
import io.github.piresrenan.orderhub.users.application.port.in.EstablishNewExternalUserUseCase;
import io.github.piresrenan.orderhub.users.application.port.in.ExternalIdentityAlreadyBoundException;
import io.github.piresrenan.orderhub.users.application.port.in.ResolveExternalIdentityQuery;
import io.github.piresrenan.orderhub.users.application.port.in.ResolveExternalIdentityUseCase;
import io.github.piresrenan.orderhub.users.application.port.out.TrustedExternalIdentityProviders;

/**
 * Coordinates the ADR-0022 retained first-operator ceremony.
 *
 * <p>Everything happens in one transaction that first locks the singleton
 * ceremony row, so every competing process, replica or rerun is arbitrated by
 * PostgreSQL. A COMPLETED ceremony is answered only from its immutable evidence
 * (operation id and request fingerprint), never from current external identity
 * bindings or current issuer trust. An OPEN ceremony requires the issuer to be
 * in the same configured trust set that JWT verification uses before any
 * authoritative write. Within the transaction the Users, Authorization and
 * bootstrap-owned writes all join the same physical transaction; any failure
 * rolls all of them back and nothing is compensated afterwards.</p>
 */
public final class FirstOperatorBootstrapService implements BootstrapFirstOperatorUseCase {

    private final TrustedExternalIdentityProviders trust;
    private final FirstOperatorBootstrapTransaction transaction;
    private final FirstOperatorCeremonyRepository ceremony;
    private final ResolveExternalIdentityUseCase resolver;
    private final EstablishNewExternalUserUseCase users;
    private final FirstOperatorPlatformAuthorityUseCase authority;
    private final Supplier<UUID> eventIds;

    /** Requires every owner contract; construction performs no persistence work. */
    public FirstOperatorBootstrapService(TrustedExternalIdentityProviders trust, FirstOperatorBootstrapTransaction transaction,
            FirstOperatorCeremonyRepository ceremony, ResolveExternalIdentityUseCase resolver,
            EstablishNewExternalUserUseCase users, FirstOperatorPlatformAuthorityUseCase authority, Supplier<UUID> eventIds) {
        this.trust = Objects.requireNonNull(trust, "trust");
        this.transaction = Objects.requireNonNull(transaction, "transaction");
        this.ceremony = Objects.requireNonNull(ceremony, "ceremony");
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.users = Objects.requireNonNull(users, "users");
        this.authority = Objects.requireNonNull(authority, "authority");
        this.eventIds = Objects.requireNonNull(eventIds, "eventIds");
    }

    /** Arbitrates the one-shot transition under the singleton lock. */
    @Override
    public FirstOperatorBootstrapOutcome bootstrap(FirstOperatorBootstrapRequest request) {
        Objects.requireNonNull(request, "request");
        var identity = new ResolveExternalIdentityQuery(request.issuer(), request.subject());
        try {
            return transaction.execute(() -> arbitrate(request, identity));
        } catch (ExternalIdentityAlreadyBoundException exception) {
            // Only reachable when the identity was bound concurrently; the transaction has rolled back.
            return FirstOperatorBootstrapOutcome.INCOMPATIBLE_EXISTING_STATE;
        }
    }

    /**
     * Runs under the singleton lock: replay from evidence when closed; otherwise require trust, fail closed on
     * existing authority or binding, and transition.
     */
    private FirstOperatorBootstrapOutcome arbitrate(FirstOperatorBootstrapRequest request, ResolveExternalIdentityQuery identity) {
        var fingerprint = FirstOperatorRequestFingerprint.of(request);
        var state = ceremony.lock();
        if (state.completed()) {
            return replay(state, request, fingerprint);
        }
        // Checked before any authoritative write; the lock itself mutates nothing.
        if (!trust.isTrusted(request.issuer())) {
            return FirstOperatorBootstrapOutcome.UNTRUSTED_ISSUER;
        }
        if (authority.platformAuthorityExists() || resolver.resolve(identity).isPresent()) {
            return FirstOperatorBootstrapOutcome.INCOMPATIBLE_EXISTING_STATE;
        }
        var operator = users.establishNew(identity).userId();
        authority.establishFirstOperatorAuthority(operator);
        ceremony.appendCompletedEvidence(Objects.requireNonNull(eventIds.get(), "eventId"), request.operationId(), operator);
        ceremony.complete(request.operationId(), operator, fingerprint);
        return FirstOperatorBootstrapOutcome.COMPLETED;
    }

    /**
     * Recognizes a rerun only from immutable ceremony evidence: the same operation id and the same exact request
     * fingerprint. Current bindings and current issuer trust are deliberately not consulted.
     */
    private static FirstOperatorBootstrapOutcome replay(FirstOperatorCeremonyState state, FirstOperatorBootstrapRequest request,
            String fingerprint) {
        var sameOperation = state.operationId().equals(request.operationId())
                && MessageDigest.isEqual(state.requestFingerprint().getBytes(StandardCharsets.US_ASCII),
                        fingerprint.getBytes(StandardCharsets.US_ASCII));
        return sameOperation
                ? FirstOperatorBootstrapOutcome.ALREADY_COMPLETED_SAME_OPERATION
                : FirstOperatorBootstrapOutcome.ALREADY_COMPLETED;
    }
}
