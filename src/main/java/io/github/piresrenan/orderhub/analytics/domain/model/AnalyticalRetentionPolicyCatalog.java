package io.github.piresrenan.orderhub.analytics.domain.model;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;

/**
 * Immutable catalog binding every analytical fact schema to the retention
 * policy currently in effect for it.
 *
 * <p>
 * Retention duration is policy configuration rather than schema metadata:
 * legal or business policy may change while a historical fact schema stays the
 * same. The catalog therefore holds the effective policy supplied to it and
 * declares no duration of its own.
 * </p>
 *
 * <p>
 * Coverage is validated against the declared fact vocabulary rather than a
 * fixed list, so introducing a fact type whose retention nobody configured
 * fails construction instead of being retained by accident. There is no
 * default or fallback policy.
 * </p>
 */
public final class AnalyticalRetentionPolicyCatalog {

    private static final String COVERAGE_REQUIRED =
            "Retention policy is required for every analytical fact type";

    private final Map<
            AnalyticalFactType,
            AnalyticalRetentionPolicy> policies;

    public AnalyticalRetentionPolicyCatalog(
            Map<
                    AnalyticalFactType,
                    AnalyticalRetentionPolicy> policies) {

        if (policies == null) {
            throw new IllegalArgumentException(
                    "Retention policies are required");
        }

        var requiredFactTypes =
                EnumSet.allOf(
                        AnalyticalFactType.class);

        if (!policies.keySet()
                .equals(
                        requiredFactTypes)) {

            throw new IllegalArgumentException(
                    COVERAGE_REQUIRED);
        }

        if (policies.values()
                .stream()
                .anyMatch(policy ->
                        policy == null)) {

            throw new IllegalArgumentException(
                    COVERAGE_REQUIRED);
        }

        // The supplied mapping is caller-owned configuration state and may be
        // mutated afterwards, so the catalog copies it rather than retaining
        // the reference.
        var copy =
                new EnumMap<
                        AnalyticalFactType,
                        AnalyticalRetentionPolicy>(
                        AnalyticalFactType.class);

        copy.putAll(
                policies);

        this.policies =
                Map.copyOf(
                        copy);
    }

    /**
     * Returns the retention policy in effect for one analytical fact schema.
     *
     * @param factType declared analytical fact vocabulary
     * @return the configured policy, never a default or fallback
     */
    public AnalyticalRetentionPolicy policyFor(
            AnalyticalFactType factType) {

        return policies.get(
                factType);
    }
}
