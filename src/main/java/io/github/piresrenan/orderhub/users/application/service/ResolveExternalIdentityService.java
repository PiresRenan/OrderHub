package io.github.piresrenan.orderhub.users.application.service;

import java.util.Optional;

import io.github.piresrenan.orderhub.users.application.port.in.ResolveExternalIdentityQuery;
import io.github.piresrenan.orderhub.users.application.port.in.ResolveExternalIdentityUseCase;
import io.github.piresrenan.orderhub.users.application.port.in.ResolvedUserIdentity;
import io.github.piresrenan.orderhub.users.application.port.in.ExternalIdentityResolutionUnavailableException;
import io.github.piresrenan.orderhub.users.application.port.out.ExternalIdentityBindingPersistenceException;
import io.github.piresrenan.orderhub.users.application.port.out.ExternalIdentityBindingRepository;
import io.github.piresrenan.orderhub.users.domain.model.ExternalIdentityBinding;

public final class ResolveExternalIdentityService
        implements ResolveExternalIdentityUseCase {

    private final ExternalIdentityBindingRepository externalIdentityBindingRepository;

    /**
     * Creates the external identity resolution service using only its
     * application-owned persistence boundary.
     *
     * @param externalIdentityBindingRepository external identity lookup boundary
     */
    public ResolveExternalIdentityService(
            ExternalIdentityBindingRepository externalIdentityBindingRepository) {

        this.externalIdentityBindingRepository =
                externalIdentityBindingRepository;
    }

    /**
     * Resolves one validated external identity to a semantically explicit
     * internal OrderHub User identity.
     *
     * <p>
     * The persisted binding is projected to an application-owned result that
     * contains only the internal User identifier. Provider identity data
     * therefore does not need to propagate to downstream consumers.
     * </p>
     *
     * <p>
     * An unknown external identity remains a normal empty query result here.
     * The consuming security boundary owns conversion of that absence into an
     * authentication failure.
     * </p>
     *
     * @param query validated exact external identity
     * @return resolved internal User identity when present, otherwise empty
     */
    @Override
    public Optional<ResolvedUserIdentity> resolve(
            ResolveExternalIdentityQuery query) {

        Optional<ExternalIdentityBinding> found;
        try {
            found = externalIdentityBindingRepository.find(query.issuer(), query.subject());
        } catch (ExternalIdentityBindingPersistenceException failure) {
            // Persistence ports stay internal; consumers see only the API classification.
            throw new ExternalIdentityResolutionUnavailableException(failure);
        }
        return found
                .map(binding ->
                        new ResolvedUserIdentity(
                                binding.userId()));
    }
}
