package io.github.piresrenan.orderhub.analytics.domain.model;

import java.util.UUID;

/**
 * Pseudonymous analytical identity of one subject inside one Tenant.
 *
 * <p>
 * The key exists so analytical facts can be correlated for the same subject
 * without carrying an operational identifier. It is deliberately a distinct
 * type from a raw {@link UUID} so an operational Staff or User identifier
 * cannot be passed where an analytical subject is expected.
 * </p>
 *
 * <p>
 * Resolution between an operational subject and this key is owned by a
 * separate mapping and is never part of an analytical fact.
 * </p>
 */
public record AnalyticalSubjectKey(
        UUID value) {

    public AnalyticalSubjectKey {

        if (value == null) {
            throw new IllegalArgumentException(
                    "Analytical subject key value is required");
        }
    }
}
