package io.github.piresrenan.orderhub.analytics.application.port.out;

import java.util.UUID;

import io.github.piresrenan.orderhub.analytics.domain.model.AnalyticalSubjectKey;

/**
 * Durable resolution of the pseudonymous analytical identity of one
 * Tenant-scoped operational subject.
 *
 * <p>
 * The mapping between an operational subject and its analytical identity is a
 * persistence concern. Only the resulting {@link AnalyticalSubjectKey} is
 * meaningful to the rest of analytics, so no mapping model is exposed here.
 * </p>
 */
@FunctionalInterface
public interface AnalyticalSubjectPseudonymRepository {

    /**
     * Returns the analytical identity already established for the tuple, or
     * durably establishes one when the tuple has no mapping yet.
     *
     * <p>
     * An identity that is already persisted for the tuple is returned as is
     * and is never replaced, so analytical facts retained under it keep
     * correlating to the same subject.
     * </p>
     *
     * @param tenantId             explicit Tenant scope of the lookup
     * @param operationalSubjectId identifier owned by the operational module
     * @return the persisted analytical identity for the tuple
     */
    AnalyticalSubjectKey resolveOrCreate(
            UUID tenantId,
            UUID operationalSubjectId);
}
