package io.github.piresrenan.orderhub.bootstrap.application.port.out;

import java.util.UUID;

/** Bootstrap-owned singleton state and append-only evidence; every method joins the caller transaction. */
public interface FirstOperatorCeremonyRepository {

    /**
     * Locks the singleton ceremony row until the caller transaction ends.
     *
     * @return current state observed under the lock
     */
    FirstOperatorCeremonyState lock();

    /**
     * Appends the single privileged success evidence row.
     *
     * @param eventId        new evidence identifier
     * @param operationId    ceremony operation identifier
     * @param operatorUserId internal User that received the initial grant
     */
    void appendCompletedEvidence(UUID eventId, UUID operationId, UUID operatorUserId);

    /**
     * Moves the singleton from OPEN to COMPLETED.
     *
     * @param operationId        ceremony operation identifier
     * @param operatorUserId     internal User that received the initial grant
     * @param requestFingerprint immutable fingerprint of the completing request
     */
    void complete(UUID operationId, UUID operatorUserId, String requestFingerprint);
}
