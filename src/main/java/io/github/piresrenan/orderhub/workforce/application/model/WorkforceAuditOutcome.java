package io.github.piresrenan.orderhub.workforce.application.model;

import org.springframework.modulith.NamedInterface;

/**
 * Committed or rejected outcome represented by workforce audit evidence.
 */
@NamedInterface("authority-change-analytics-source")
public enum WorkforceAuditOutcome {

    APPLIED,
    DENIED
}
