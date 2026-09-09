package io.github.piresrenan.orderhub.users.application.service;

import java.util.Objects;

import io.github.piresrenan.orderhub.users.application.port.in.BindExternalIdentityCommand;
import io.github.piresrenan.orderhub.users.application.port.in.BindExternalIdentityUseCase;
import io.github.piresrenan.orderhub.users.application.port.in.CreateUserUseCase;
import io.github.piresrenan.orderhub.users.application.port.in.ResolveExternalIdentityQuery;
import io.github.piresrenan.orderhub.users.application.port.in.ResolveExternalIdentityUseCase;
import io.github.piresrenan.orderhub.users.application.port.in.ResolveOrCreateExternalUserUseCase;
import io.github.piresrenan.orderhub.users.application.port.in.ResolvedUserIdentity;
import io.github.piresrenan.orderhub.users.application.port.out.ExternalIdentityUserProvisioningCoordinator;

/**
 * Resolves one exact external identity to an internal OrderHub User and
 * establishes that User, plus its durable binding, when the identity has not
 * been seen before.
 *
 * <p>The whole resolve/create/bind decision executes inside one provisioning
 * coordination scope. The service therefore reads binding state that is already
 * serialized against competing scopes for the same issuer/subject pair, and
 * needs no duplicate recovery of its own.</p>
 *
 * <p>An existing binding is reused exactly as resolved: no User is created and
 * no binding is written again. Creation and binding failures propagate
 * unchanged, because undoing partially established state is the coordination
 * scope's responsibility rather than this service's.</p>
 */
public final class ResolveOrCreateExternalUserService
        implements ResolveOrCreateExternalUserUseCase {

    private final ExternalIdentityUserProvisioningCoordinator coordinator;
    private final ResolveExternalIdentityUseCase resolver;
    private final CreateUserUseCase creator;
    private final BindExternalIdentityUseCase binder;

    /**
     * Creates the resolve-or-create service from its coordination scope and the
     * Users application boundaries it composes.
     *
     * @param coordinator serialized external identity provisioning scope
     * @param resolver    exact external identity resolution boundary
     * @param creator     internal User creation boundary
     * @param binder      external identity association boundary
     */
    public ResolveOrCreateExternalUserService(
            ExternalIdentityUserProvisioningCoordinator coordinator,
            ResolveExternalIdentityUseCase resolver,
            CreateUserUseCase creator,
            BindExternalIdentityUseCase binder) {

        this.coordinator =
                Objects.requireNonNull(
                        coordinator,
                        "coordinator");

        this.resolver =
                Objects.requireNonNull(
                        resolver,
                        "resolver");

        this.creator =
                Objects.requireNonNull(
                        creator,
                        "creator");

        this.binder =
                Objects.requireNonNull(
                        binder,
                        "binder");
    }

    /**
     * Resolves the internal User for one exact external identity, creating and
     * binding it exactly once when no binding exists yet.
     *
     * @param query exact external identity pair
     * @return stable internal User identity associated with the pair
     */
    @Override
    public ResolvedUserIdentity resolveOrCreate(
            ResolveExternalIdentityQuery query) {

        Objects.requireNonNull(
                query,
                "query");

        return coordinator.executeSerialized(
                query.issuer(),
                query.subject(),
                () -> resolveOrEstablish(
                        query));
    }

    private ResolvedUserIdentity resolveOrEstablish(
            ResolveExternalIdentityQuery query) {

        var existing =
                resolver.resolve(
                        query);

        if (existing.isPresent()) {
            return existing.get();
        }

        var created =
                creator.create();

        binder.bind(
                new BindExternalIdentityCommand(
                        query.issuer(),
                        query.subject(),
                        created.userId()));

        return new ResolvedUserIdentity(
                created.userId());
    }
}
