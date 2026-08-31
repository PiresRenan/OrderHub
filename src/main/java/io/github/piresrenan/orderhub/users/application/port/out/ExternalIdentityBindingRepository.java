package io.github.piresrenan.orderhub.users.application.port.out;

import java.util.Optional;

import io.github.piresrenan.orderhub.users.domain.model.ExternalIdentityBinding;

/**
 * Defines the application-owned persistence boundary for external identity
 * bindings.
 */
public interface ExternalIdentityBindingRepository {

    /**
     * Persists one valid association between external identity and internal
     * User identity.
     *
     * <p>
     * Durable uniqueness and referential-integrity constraints belong to the
     * persistence implementation because they depend on previously stored
     * state.
     * </p>
     *
     * @param binding valid external identity association
     * @return persisted binding
     */
    ExternalIdentityBinding save(
            ExternalIdentityBinding binding);

    /**
     * Finds one binding by its exact issuer/subject identity pair.
     *
     * @param issuer  exact external identity issuer
     * @param subject exact external identity subject
     * @return matching binding when present, otherwise empty
     */
    Optional<ExternalIdentityBinding> find(
            String issuer,
            String subject);
}
