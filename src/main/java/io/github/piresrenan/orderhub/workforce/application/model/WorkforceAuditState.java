package io.github.piresrenan.orderhub.workforce.application.model;

import java.util.UUID;

import io.github.piresrenan.orderhub.authorization.domain.model.AuthorityBand;
import io.github.piresrenan.orderhub.workforce.domain.model.StaffStatus;

/**
 * Privacy-bounded organizational state captured around one workforce audit fact.
 *
 * <p>
 * Every component is an opaque identifier or closed policy vocabulary.
 * Human-readable Staff PII and request payloads do not belong here.
 * </p>
 */
public record WorkforceAuditState(
        StaffStatus status,
        UUID departmentId,
        UUID positionId,
        UUID supervisorStaffId,
        AuthorityBand authorityBand) {

    public static WorkforceAuditState empty() {

        return new WorkforceAuditState(
                null,
                null,
                null,
                null,
                null);
    }
}
