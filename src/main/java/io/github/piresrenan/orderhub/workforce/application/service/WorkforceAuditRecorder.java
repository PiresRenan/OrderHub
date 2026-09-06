package io.github.piresrenan.orderhub.workforce.application.service;

import java.util.Objects;

import io.github.piresrenan.orderhub.workforce.application.model.WorkforceAuditEvidence;
import io.github.piresrenan.orderhub.workforce.application.model.WorkforceAuthorityChangeAuditRecorded;
import io.github.piresrenan.orderhub.workforce.application.port.out.WorkforceAuditRepository;
import io.github.piresrenan.orderhub.workforce.application.port.out.WorkforceAuthorityChangeAuditNotificationPublisher;

/**
 * Records one privilege-significant workforce audit event and announces it.
 *
 * <p>
 * Every workforce path that appends audit evidence goes through here, so the
 * pairing of operational evidence and its announcement is a single invariant
 * rather than a rule each service is trusted to remember.
 * </p>
 *
 * <p>
 * The recorder owns no transaction. It joins whatever boundary its caller
 * already opened, which is what makes the announcement share the fate of the
 * operation it describes: an operation that rolls back leaves no audit row and
 * no announcement behind.
 * </p>
 */
public final class WorkforceAuditRecorder {

    private final WorkforceAuditRepository auditRepository;

    private final WorkforceAuthorityChangeAuditNotificationPublisher
            notificationPublisher;

    public WorkforceAuditRecorder(
            WorkforceAuditRepository auditRepository,
            WorkforceAuthorityChangeAuditNotificationPublisher
                    notificationPublisher) {

        this.auditRepository =
                Objects.requireNonNull(
                        auditRepository,
                        "auditRepository");

        this.notificationPublisher =
                Objects.requireNonNull(
                        notificationPublisher,
                        "notificationPublisher");
    }

    /**
     * Appends the evidence, then announces it.
     *
     * <p>
     * The order is part of the contract: the announcement is only a reference
     * to an operational audit event, so it must never be raised before the
     * application has attempted to append the event it points at.
     * </p>
     *
     * <p>
     * A publication failure is deliberately not caught. Registration happens
     * inside the caller's transaction, so letting it propagate is what keeps a
     * committed audit event from existing without its announcement.
     * </p>
     */
    public void record(
            WorkforceAuditEvidence evidence) {

        Objects.requireNonNull(
                evidence,
                "evidence");

        auditRepository.append(
                evidence);

        notificationPublisher.publish(
                new WorkforceAuthorityChangeAuditRecorded(
                        evidence.tenantId(),
                        evidence.auditEventId()));
    }
}
