package io.github.piresrenan.orderhub.security.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import io.github.piresrenan.orderhub.security.application.port.in.ResolveAuthenticatedUserQuery;
import io.github.piresrenan.orderhub.security.application.port.in.ResolveAuthenticatedUserUseCase;
import io.github.piresrenan.orderhub.users.application.port.in.ResolveExternalIdentityQuery;
import io.github.piresrenan.orderhub.users.application.port.in.ResolveExternalIdentityUseCase;
import io.github.piresrenan.orderhub.users.application.port.in.ResolvedUserIdentity;

class ResolveAuthenticatedUserServiceTest {

    @Test
    void resolvesExternalIdentityToInternalAuthenticatedPrincipal() {
        // Why: authentication may reach downstream application code only after the
        // provider identity has been mapped to an internal OrderHub User.
        // Covers: Security -> Users application delegation and projection into
        // AuthenticatedUserPrincipal.
        // Prevents: issuer, subject or provider identity being treated directly as
        // the authenticated application principal.

        var issuer = "https://issuer.example.test";
        var subject = "synthetic-subject-001";
        var userId = UUID.randomUUID();

        var calls = new AtomicInteger();
        var delegatedQuery =
                new AtomicReference<ResolveExternalIdentityQuery>();

        ResolveExternalIdentityUseCase usersResolver = query -> {
            calls.incrementAndGet();
            delegatedQuery.set(query);

            return Optional.of(
                    new ResolvedUserIdentity(
                            userId));
        };

        ResolveAuthenticatedUserUseCase useCase =
                new ResolveAuthenticatedUserService(
                        usersResolver);

        var result = useCase.resolve(
                new ResolveAuthenticatedUserQuery(
                        issuer,
                        subject));

        assertThat(result)
                .isPresent();

        assertThat(result.orElseThrow().userId())
                .isEqualTo(userId);

        assertThat(calls.get())
                .isEqualTo(1);

        assertThat(delegatedQuery.get().issuer())
                .isEqualTo(issuer);

        assertThat(delegatedQuery.get().subject())
                .isEqualTo(subject);
    }

    @Test
    void returnsEmptyWhenExternalIdentityIsNotMapped() {
        // Why: an unknown external identity is a normal resolution outcome at this
        // application boundary; HTTP authentication semantics belong to an adapter.
        // Covers: Users Optional.empty propagation without inventing a principal.
        // Prevents: Security fabricating an internal User or prematurely coupling
        // this service to HTTP 401 behavior.

        ResolveExternalIdentityUseCase usersResolver =
                query -> Optional.empty();

        ResolveAuthenticatedUserUseCase useCase =
                new ResolveAuthenticatedUserService(
                        usersResolver);

        var result = useCase.resolve(
                new ResolveAuthenticatedUserQuery(
                        "https://issuer.example.test",
                        "synthetic-unmapped-subject"));

        assertThat(result)
                .isEmpty();
    }

    @Test
    void rejectsMissingUsersIdentityResolverDependency() {
        // Why: authenticated User resolution cannot operate without the Users-owned
        // external identity mapping contract.
        // Covers: mandatory intermodule application dependency.
        // Prevents: a partially constructed Security service failing later during an
        // authentication request.

        assertThatThrownBy(() ->
                new ResolveAuthenticatedUserService(
                        null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(
                        "External identity resolver is required");
    }
}
