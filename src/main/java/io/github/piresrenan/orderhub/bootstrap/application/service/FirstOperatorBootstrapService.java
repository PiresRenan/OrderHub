package io.github.piresrenan.orderhub.bootstrap.application.service;

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
 * <p>Issuer trust is decided first, from the same configured issuer set that
 * JWT verification uses. Everything else happens in one transaction that
 * first locks the singleton ceremony row, so every competing process, replica
 * or rerun is arbitrated by PostgreSQL. Within it the Users, Authorization and
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

    /** Rejects untrusted issuers before any transaction, then arbitrates the one-shot transition. */
    @Override
    public FirstOperatorBootstrapOutcome bootstrap(FirstOperatorBootstrapRequest request) {
        Objects.requireNonNull(request, "request");
        if (!trust.isTrusted(request.issuer())) {
            return FirstOperatorBootstrapOutcome.UNTRUSTED_ISSUER;
        }
        var identity = new ResolveExternalIdentityQuery(request.issuer(), request.subject());
        try {
            return transaction.execute(() -> arbitrate(request, identity));
        } catch (ExternalIdentityAlreadyBoundException exception) {
            // Only reachable when the identity was bound concurrently; the transaction has rolled back.
            return FirstOperatorBootstrapOutcome.INCOMPATIBLE_EXISTING_STATE;
        }
    }

    /** Runs under the singleton lock: replay when closed, fail closed on existing authority, else transition. */
    private FirstOperatorBootstrapOutcome arbitrate(FirstOperatorBootstrapRequest request, ResolveExternalIdentityQuery identity) {
        var state = ceremony.lock();
        if (state.completed()) {
            return replay(state, request, identity);
        }
        if (authority.platformAuthorityExists() || resolver.resolve(identity).isPresent()) {
            return FirstOperatorBootstrapOutcome.INCOMPATIBLE_EXISTING_STATE;
        }
        var operator = users.establishNew(identity).userId();
        authority.establishFirstOperatorAuthority(operator);
        ceremony.appendCompletedEvidence(Objects.requireNonNull(eventIds.get(), "eventId"), request.operationId(), operator);
        ceremony.complete(request.operationId(), operator);
        return FirstOperatorBootstrapOutcome.COMPLETED;
    }

    /** A rerun is recognized only when both the operation and the exact identity match the completed transition. */
    private FirstOperatorBootstrapOutcome replay(FirstOperatorCeremonyState state, FirstOperatorBootstrapRequest request,
            ResolveExternalIdentityQuery identity) {
        var sameOperation = state.operationId().equals(request.operationId())
                && resolver.resolve(identity).map(user -> user.userId().equals(state.operatorUserId())).orElse(false);
        return sameOperation
                ? FirstOperatorBootstrapOutcome.ALREADY_COMPLETED_SAME_OPERATION
                : FirstOperatorBootstrapOutcome.ALREADY_COMPLETED;
    }
}
