package io.github.piresrenan.orderhub.workforce.application.model;

import org.springframework.modulith.NamedInterface;

/**
 * Bounded vocabulary for privilege-significant workforce audit facts.
 */
@NamedInterface("authority-change-analytics-source")
public enum WorkforceAuditActionType {

    STAFF_ACTIVATED,
    STAFF_DEACTIVATED,
    DEPARTMENT_CHANGED,
    POSITION_CHANGED,
    POSITION_AUTHORITY_CHANGED,
    SUPERVISOR_CHANGED,
    PRIVILEGED_MUTATION
}
