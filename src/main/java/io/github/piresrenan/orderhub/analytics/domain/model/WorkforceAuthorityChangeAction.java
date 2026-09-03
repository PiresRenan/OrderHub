package io.github.piresrenan.orderhub.analytics.domain.model;

/**
 * Closed analytical vocabulary for privilege-significant workforce actions.
 *
 * <p>
 * The vocabulary is owned by analytics rather than reused from the workforce
 * audit model. Analytics versions and retains this vocabulary on analytical
 * terms, and only the actions actually produced by a concrete workforce
 * workflow are represented.
 * </p>
 *
 * <p>
 * {@code POSITION_CHANGED} already means the authority band was unchanged and
 * {@code POSITION_AUTHORITY_CHANGED} already means it changed. The bands
 * themselves are therefore deliberately not analytical facts.
 * </p>
 */
public enum WorkforceAuthorityChangeAction {

    POSITION_CHANGED,

    POSITION_AUTHORITY_CHANGED,

    PRIVILEGED_MUTATION
}
