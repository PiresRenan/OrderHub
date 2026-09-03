package io.github.piresrenan.orderhub.workforce.application.model;

/**
 * Bounded vocabulary for privilege-significant workforce audit facts.
 */
public enum WorkforceAuditActionType {

    STAFF_ACTIVATED,
    STAFF_DEACTIVATED,
    DEPARTMENT_CHANGED,
    POSITION_CHANGED,
    POSITION_AUTHORITY_CHANGED,
    SUPERVISOR_CHANGED,
    PRIVILEGED_MUTATION
}
