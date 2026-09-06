package io.github.piresrenan.orderhub.workforce.adapter.out.notification.spring;

import java.util.Objects;

import org.springframework.context.ApplicationEventPublisher;

import io.github.piresrenan.orderhub.workforce.application.model.WorkforceAuthorityChangeAuditRecorded;
import io.github.piresrenan.orderhub.workforce.application.port.out.WorkforceAuthorityChangeAuditNotificationPublisher;

/**
 * Spring adapter for the workforce authority-change notification boundary.
 *
 * <p>
 * Durability is not implemented here. Spring Modulith's persistent event
 * multicaster registers one publication per transactional after-commit target
 * while the publishing transaction is still open, so publishing through the
 * ordinary {@link ApplicationEventPublisher} is what couples the announcement
 * to the source transaction. Reaching for the publication registry directly
 * would duplicate that mechanism and move a framework concern into the module.
 * </p>
 */
public final class SpringWorkforceAuthorityChangeAuditNotificationPublisher
        implements WorkforceAuthorityChangeAuditNotificationPublisher {

    private final ApplicationEventPublisher applicationEventPublisher;

    public SpringWorkforceAuthorityChangeAuditNotificationPublisher(
            ApplicationEventPublisher applicationEventPublisher) {

        this.applicationEventPublisher =
                Objects.requireNonNull(
                        applicationEventPublisher,
                        "applicationEventPublisher");
    }

    @Override
    public void publish(
            WorkforceAuthorityChangeAuditRecorded notification) {

        Objects.requireNonNull(
                notification,
                "notification");

        applicationEventPublisher.publishEvent(
                notification);
    }
}
