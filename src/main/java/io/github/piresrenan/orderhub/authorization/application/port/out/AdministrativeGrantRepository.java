package io.github.piresrenan.orderhub.authorization.application.port.out;

import io.github.piresrenan.orderhub.authorization.domain.model.AdministrativeGrant;

public interface AdministrativeGrantRepository {

    AdministrativeGrantMutationResult grant(
            AdministrativeGrant grant);

    AdministrativeGrantMutationResult revoke(
            AdministrativeGrant grant);

    boolean exists(
            AdministrativeGrant grant);
}
