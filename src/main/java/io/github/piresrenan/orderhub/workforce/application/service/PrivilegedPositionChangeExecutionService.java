package io.github.piresrenan.orderhub.workforce.application.service;

import java.util.Objects;

import io.github.piresrenan.orderhub.workforce.application.model.PrivilegedPositionChangeCommand;
import io.github.piresrenan.orderhub.workforce.application.model.PrivilegedPositionChangeRequest;
import io.github.piresrenan.orderhub.workforce.application.model.WorkforceAuditActionType;
import io.github.piresrenan.orderhub.workforce.application.model.WorkforceAuditEvidence;
import io.github.piresrenan.orderhub.workforce.application.model.WorkforceAuditOutcome;
import io.github.piresrenan.orderhub.workforce.application.model.WorkforceAuditState;
import io.github.piresrenan.orderhub.workforce.application.model.WorkforcePositionChangeSnapshot;
import io.github.piresrenan.orderhub.workforce.application.port.out.WorkforceAuditRepository;
import io.github.piresrenan.orderhub.workforce.application.port.out.WorkforcePositionChangeRepository;
import io.github.piresrenan.orderhub.workforce.application.port.out.WorkforceTransactionExecutor;
import io.github.piresrenan.orderhub.workforce.domain.model.EffectiveWorkforceAuthority;
import io.github.piresrenan.orderhub.workforce.domain.model.PositionChange;
import io.github.piresrenan.orderhub.workforce.domain.model.PositionChangeType;
import io.github.piresrenan.orderhub.workforce.domain.model.WorkforceMutationDecision;

/**
 * Executes one privileged Staff position change from database-authoritative
 * organizational facts.
 *
 * <p>
 * The caller supplies identity, authorization outcome, delegation envelope and
 * audit correlation identifiers. Current and requested workforce authority are
 * resolved from persistence inside the same transaction that performs the
 * mutation and appends audit evidence.
 * </p>
 */
public final class PrivilegedPositionChangeExecutionService {

    private static final String DENIED_REASON =
            "PRIVILEGED_POLICY_DENIED";

    private static final String POSITION_UNCHANGED_REASON =
            "POSITION_UNCHANGED";

    private final WorkforceTransactionExecutor transactionExecutor;

    private final WorkforcePositionChangeRepository positionRepository;

    private final WorkforceAuditRepository auditRepository;

    private final PrivilegedWorkforceMutationAuthorizationService
            authorizationService;

    public PrivilegedPositionChangeExecutionService(
            WorkforceTransactionExecutor transactionExecutor,
            WorkforcePositionChangeRepository positionRepository,
            WorkforceAuditRepository auditRepository,
            PrivilegedWorkforceMutationAuthorizationService authorizationService) {

        this.transactionExecutor =
                Objects.requireNonNull(
                        transactionExecutor,
                        "transactionExecutor");

        this.positionRepository =
                Objects.requireNonNull(
                        positionRepository,
                        "positionRepository");

        this.auditRepository =
                Objects.requireNonNull(
                        auditRepository,
                        "auditRepository");

        this.authorizationService =
                Objects.requireNonNull(
                        authorizationService,
                        "authorizationService");
    }

    public WorkforceMutationDecision execute(
            PrivilegedPositionChangeCommand command) {

        Objects.requireNonNull(
                command,
                "command");

        return transactionExecutor.execute(
                () -> executeInsideTransaction(
                        command));
    }

    private WorkforceMutationDecision executeInsideTransaction(
            PrivilegedPositionChangeCommand command) {

        var snapshot =
                positionRepository.loadForUpdate(
                        command.tenantId(),
                        command.actorStaffId(),
                        command.targetStaffId(),
                        command.targetPositionId());

        var positionChange =
                positionChange(
                        snapshot);

        var request =
                new PrivilegedPositionChangeRequest(
                        command.actorStaffId(),
                        command.targetStaffId(),
                        command.tenantId(),
                        positionChange,
                        command.actorPrivilegedAuthorizationAllowed());

        var actorAuthority =
                snapshot.actorStaff().isActive()
                        ? EffectiveWorkforceAuthority.active(
                                snapshot.actorPosition()
                                        .authorityBand(),
                                snapshot.actorPosition()
                                        .permissionEnvelope())
                        : EffectiveWorkforceAuthority.none();

        var decision =
                authorizationService.authorize(
                        request,
                        snapshot.actorStaff(),
                        snapshot.targetStaff(),
                        actorAuthority,
                        command.actorDelegationEnvelope());

        if (decision != WorkforceMutationDecision.ALLOW) {

            auditRepository.append(
                    deniedEvidence(
                            command,
                            snapshot,
                            DENIED_REASON));

            return WorkforceMutationDecision.DENY;
        }

        if (snapshot.currentTargetPosition()
                .positionId()
                .equals(
                        snapshot.requestedTargetPosition()
                                .positionId())) {

            auditRepository.append(
                    deniedEvidence(
                            command,
                            snapshot,
                            POSITION_UNCHANGED_REASON));

            return WorkforceMutationDecision.DENY;
        }

        positionRepository.changePosition(
                command.tenantId(),
                command.targetStaffId(),
                snapshot.currentTargetPosition()
                        .positionId(),
                snapshot.requestedTargetPosition()
                        .positionId());

        auditRepository.append(
                appliedEvidence(
                        command,
                        snapshot));

        return WorkforceMutationDecision.ALLOW;
    }

    private PositionChange positionChange(
            WorkforcePositionChangeSnapshot snapshot) {

        var before =
                snapshot.currentTargetPosition();

        var after =
                snapshot.requestedTargetPosition();

        return new PositionChange(
                snapshot.targetStaff()
                        .staffId(),
                snapshot.targetStaff()
                        .tenantId(),
                before.positionId(),
                after.positionId(),
                before.authorityBand(),
                after.authorityBand(),
                before.permissionEnvelope(),
                after.permissionEnvelope(),
                classifyChange(
                        before.authorityBand(),
                        after.authorityBand()));
    }

    private PositionChangeType classifyChange(
            io.github.piresrenan.orderhub.authorization.domain.model.AuthorityBand before,
            io.github.piresrenan.orderhub.authorization.domain.model.AuthorityBand after) {

        if (before == after) {
            return PositionChangeType.LATERAL;
        }

        if (after.isAtLeast(
                before)) {

            return PositionChangeType.PROMOTION;
        }

        return PositionChangeType.DEMOTION;
    }

    private WorkforceAuditEvidence deniedEvidence(
            PrivilegedPositionChangeCommand command,
            WorkforcePositionChangeSnapshot snapshot,
            String reasonCode) {

        var state =
                state(
                        snapshot,
                        snapshot.currentTargetPosition());

        return new WorkforceAuditEvidence(
                command.auditEventId(),
                command.tenantId(),
                command.actorStaffId(),
                command.targetStaffId(),
                WorkforceAuditActionType.PRIVILEGED_MUTATION,
                WorkforceAuditOutcome.DENIED,
                reasonCode,
                command.correlationId(),
                state,
                state);
    }

    private WorkforceAuditEvidence appliedEvidence(
            PrivilegedPositionChangeCommand command,
            WorkforcePositionChangeSnapshot snapshot) {

        var before =
                snapshot.currentTargetPosition();

        var after =
                snapshot.requestedTargetPosition();

        var action =
                before.authorityBand()
                                == after.authorityBand()
                        ? WorkforceAuditActionType.POSITION_CHANGED
                        : WorkforceAuditActionType.POSITION_AUTHORITY_CHANGED;

        return new WorkforceAuditEvidence(
                command.auditEventId(),
                command.tenantId(),
                command.actorStaffId(),
                command.targetStaffId(),
                action,
                WorkforceAuditOutcome.APPLIED,
                null,
                command.correlationId(),
                state(
                        snapshot,
                        before),
                state(
                        snapshot,
                        after));
    }

    private WorkforceAuditState state(
            WorkforcePositionChangeSnapshot snapshot,
            io.github.piresrenan.orderhub.workforce.domain.model.JobPosition position) {

        return new WorkforceAuditState(
                snapshot.targetStaff()
                        .status(),
                snapshot.targetDepartmentId(),
                position.positionId(),
                null,
                position.authorityBand());
    }
}
