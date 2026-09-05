package io.github.piresrenan.orderhub.analytics.domain.model;

/**
 * Closed analytical vocabulary identifying the schema of a persisted fact.
 *
 * <p>
 * The vocabulary is owned by analytics and declares only the fact type that a
 * concrete analytical contract currently represents. A bounded type keeps a
 * persisted row interpretable without admitting arbitrary text as schema
 * identity.
 * </p>
 */
public enum AnalyticalFactType {

    WORKFORCE_AUTHORITY_CHANGE
}
