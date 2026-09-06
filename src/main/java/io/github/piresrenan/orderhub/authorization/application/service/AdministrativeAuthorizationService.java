package io.github.piresrenan.orderhub.authorization.application.service;

import java.util.Objects;

import io.github.piresrenan.orderhub.authorization.application.port.in.administration.AuthorizeAdministrativeActionUseCase;
import io.github.piresrenan.orderhub.authorization.application.port.out.AdministrativeGrantRepository;
import io.github.piresrenan.orderhub.authorization.domain.model.AdministrativeGrant;
import io.github.piresrenan.orderhub.authorization.domain.model.AuthorizationDecision;

public final class AdministrativeAuthorizationService
        implements AuthorizeAdministrativeActionUseCase {

    private final AdministrativeGrantRepository grants;

    public AdministrativeAuthorizationService(
            AdministrativeGrantRepository grants) {

        this.grants =
                Objects.requireNonNull(
                        grants,
                        "grants");
    }

    @Override
    public AuthorizationDecision authorize(
            AdministrativeGrant requiredGrant) {

        Objects.requireNonNull(
                requiredGrant,
                "requiredGrant");

        if (grants.exists(
                requiredGrant)) {

            return AuthorizationDecision.ALLOW;
        }

        return AuthorizationDecision.DENY;
    }
}
