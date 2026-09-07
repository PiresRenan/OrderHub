package io.github.piresrenan.orderhub.tenants.application.port.out;

import io.github.piresrenan.orderhub.tenants.application.model.TenantAdministrativeAuditEvidence;

public interface TenantAdministrativeAuditRepository {
    void append(TenantAdministrativeAuditEvidence evidence);
}
