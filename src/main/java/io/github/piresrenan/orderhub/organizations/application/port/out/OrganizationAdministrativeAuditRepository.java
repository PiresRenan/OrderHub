package io.github.piresrenan.orderhub.organizations.application.port.out;

import io.github.piresrenan.orderhub.organizations.application.model.OrganizationAdministrativeAuditEvidence;

public interface OrganizationAdministrativeAuditRepository {
    void append(OrganizationAdministrativeAuditEvidence evidence);
}
