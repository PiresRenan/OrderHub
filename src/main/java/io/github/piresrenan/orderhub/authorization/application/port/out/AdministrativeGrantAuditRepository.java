package io.github.piresrenan.orderhub.authorization.application.port.out;

import io.github.piresrenan.orderhub.authorization.application.model.AdministrativeGrantAuditEvidence;

public interface AdministrativeGrantAuditRepository {

    void append(
            AdministrativeGrantAuditEvidence evidence);
}
