package io.github.piresrenan.orderhub.analytics.domain.model;

/**
 * Closed vocabulary describing the privacy classification of analytical data.
 *
 * <p>
 * The vocabulary is owned by analytics and declares only the classification a
 * current analytical fact schema requires. A bounded classification lets
 * retention and consumer boundaries be enforced rather than inferred.
 * </p>
 */
public enum AnalyticalDataClassification {

    PSEUDONYMOUS
}
