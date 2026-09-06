package io.github.piresrenan.orderhub.workforce.application.service;

import java.util.Objects;

import io.github.piresrenan.orderhub.workforce.application.model.WorkforceAuditEvidence;
import io.github.piresrenan.orderhub.workforce.application.port.out.WorkforceTransactionExecutor;

/**
 * Coordinates one workforce mutation and its audit evidence under the same
 * application-owned transaction boundary.
 */
public final class AuditedWorkforceMutationService {

    private final WorkforceTransactionExecutor transactionExecutor;

    private final WorkforceAuditRecorder auditRecorder;

    public AuditedWorkforceMutationService(
            WorkforceTransactionExecutor transactionExecutor,
            WorkforceAuditRecorder auditRecorder) {

        this.transactionExecutor =
                Objects.requireNonNull(
                        transactionExecutor,
                        "transactionExecutor");

        this.auditRecorder =
                Objects.requireNonNull(
                        auditRecorder,
                        "auditRecorder");
    }

    public void execute(
            Runnable mutation,
            WorkforceAuditEvidence evidence) {

        Objects.requireNonNull(
                mutation,
                "mutation");

        Objects.requireNonNull(
                evidence,
                "evidence");

        transactionExecutor.execute(
                () -> {
                    mutation.run();

                    auditRecorder.record(
                            evidence);

                    return null;
                });
    }
}
