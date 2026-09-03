package io.github.piresrenan.orderhub.workforce.application.port.out;

import java.util.UUID;

import io.github.piresrenan.orderhub.workforce.application.model.WorkforcePositionChangeSnapshot;

/**
 * PostgreSQL-facing authority for concrete workforce position mutation facts.
 */
public interface WorkforcePositionChangeRepository {

    WorkforcePositionChangeSnapshot loadForUpdate(
            UUID tenantId,
            UUID actorStaffId,
            UUID targetStaffId,
            UUID targetPositionId);

    void changePosition(
            UUID tenantId,
            UUID targetStaffId,
            UUID expectedCurrentPositionId,
            UUID targetPositionId);
}
