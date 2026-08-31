package io.github.piresrenan.orderhub.users.application.service;

import io.github.piresrenan.orderhub.users.application.port.in.BindExternalIdentityCommand;
import io.github.piresrenan.orderhub.users.application.port.in.BindExternalIdentityUseCase;
import io.github.piresrenan.orderhub.users.application.port.out.ExternalIdentityBindingRepository;
import io.github.piresrenan.orderhub.users.domain.model.ExternalIdentityBinding;

public final class BindExternalIdentityService
        implements BindExternalIdentityUseCase {

    private final ExternalIdentityBindingRepository externalIdentityBindingRepository;

    /**
     * Creates the external identity binding service using only its
     * application-owned persistence boundary.
     *
     * @param externalIdentityBindingRepository persistence boundary for external
     *                                          identity associations
     */
    public BindExternalIdentityService(
            ExternalIdentityBindingRepository externalIdentityBindingRepository) {

        this.externalIdentityBindingRepository =
                externalIdentityBindingRepository;
    }

    /**
     * Coordinates domain construction and persistence for one external identity
     * association.
     *
     * <p>
     * Domain construction occurs before persistence so structurally invalid
     * issuer, subject or User identity never crosses the repository boundary.
     * The service deliberately performs no normalization and no pre-insert User
     * existence lookup.
     * </p>
     *
     * @param command complete external/internal identity association request
     * @return successfully created and persisted binding
     * @throws IllegalArgumentException when the binding domain invariants reject
     *                                  the supplied identities
     */
    @Override
    public ExternalIdentityBinding bind(
            BindExternalIdentityCommand command) {

        var binding = ExternalIdentityBinding.create(
                command.issuer(),
                command.subject(),
                command.userId());

        return externalIdentityBindingRepository.save(
                binding);
    }
}
