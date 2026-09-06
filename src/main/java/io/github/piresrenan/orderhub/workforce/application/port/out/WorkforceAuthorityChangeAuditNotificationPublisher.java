package io.github.piresrenan.orderhub.workforce.application.port.out;

import io.github.piresrenan.orderhub.workforce.application.model.WorkforceAuthorityChangeAuditRecorded;

/**
 * Boundary through which workforce announces that it recorded one
 * privilege-significant audit event.
 *
 * <p>
 * The port deliberately says nothing about how the announcement becomes
 * durable, when it is delivered, or what happens if delivery fails. Those are
 * adapter concerns. The application only states that the announcement belongs
 * to the transaction its caller already owns.
 * </p>
 */
@FunctionalInterface
public interface WorkforceAuthorityChangeAuditNotificationPublisher {

    void publish(WorkforceAuthorityChangeAuditRecorded notification);
}
