package io.github.piresrenan.orderhub.authorization.application.port.in.administration;

import io.github.piresrenan.orderhub.authorization.domain.model.AdministrativeGrant;
import io.github.piresrenan.orderhub.authorization.domain.model.AuthorizationDecision;

public interface AuthorizeAdministrativeActionUseCase {

    AuthorizationDecision authorize(
            AdministrativeGrant requiredGrant);
}
