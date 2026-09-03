package io.github.piresrenan.orderhub.workforce.application.port.out;

import io.github.piresrenan.orderhub.workforce.application.model.WorkforceAuditEvidence;

/**
 * Append-only workforce audit persistence boundary.
 */
public interface WorkforceAuditRepository {

    void append(WorkforceAuditEvidence evidence);
}
