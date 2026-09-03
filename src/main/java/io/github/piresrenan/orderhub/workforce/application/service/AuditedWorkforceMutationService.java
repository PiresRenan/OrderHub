package io.github.piresrenan.orderhub.workforce.application.service;

import java.util.Objects;

import io.github.piresrenan.orderhub.workforce.application.model.WorkforceAuditEvidence;
import io.github.piresrenan.orderhub.workforce.application.port.out.WorkforceAuditRepository;
import io.github.piresrenan.orderhub.workforce.application.port.out.WorkforceTransactionExecutor;

/**
 * Coordinates one workforce mutation and its audit evidence under the same
 * application-owned transaction boundary.
 */
public final class AuditedWorkforceMutationService {

    private final WorkforceTransactionExecutor transactionExecutor;

    private final WorkforceAuditRepository auditRepository;

    public AuditedWorkforceMutationService(
            WorkforceTransactionExecutor transactionExecutor,
            WorkforceAuditRepository auditRepository) {

        this.transactionExecutor =
                Objects.requireNonNull(
                        transactionExecutor,
                        "transactionExecutor");

        this.auditRepository =
                Objects.requireNonNull(
                        auditRepository,
                        "auditRepository");
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

                    auditRepository.append(
                            evidence);

                    return null;
                });
    }
}
