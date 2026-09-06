package io.github.piresrenan.orderhub.authorization.application.port.in.administration;

import java.util.UUID;

import io.github.piresrenan.orderhub.authorization.domain.model.AdministrativeGrant;

public interface MutateAdministrativeGrantUseCase {
    void grant(
            UUID actorUserId, AdministrativeGrant grant, UUID correlationId);

    void revoke(
            UUID actorUserId, AdministrativeGrant grant, UUID correlationId);
}
