package io.github.piresrenan.orderhub.analytics.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.InvocationTargetException;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

class AnalyticalRetentionPolicyCatalogTest {

    private static final String CATALOG_TYPE =
            "io.github.piresrenan.orderhub.analytics.domain.model"
                    + ".AnalyticalRetentionPolicyCatalog";

    /**
     * Synthetic fixture only. This duration carries no production retention
     * authority; the effective window remains a policy decision outside this
     * contract. The invariant under test is coverage and resolution, never the
     * legal or business number.
     */
    private static final AnalyticalRetentionPolicy FIXTURE_POLICY =
            new AnalyticalRetentionPolicy(
                    Duration.ofDays(1));

    private static Map<AnalyticalFactType, AnalyticalRetentionPolicy> completeCatalog() {

        var policies =
                new EnumMap<AnalyticalFactType, AnalyticalRetentionPolicy>(
                        AnalyticalFactType.class);

        for (var factType : AnalyticalFactType.values()) {
            policies.put(
                    factType,
                    FIXTURE_POLICY);
        }

        return policies;
    }

    private static Object newCatalog(
            Map<AnalyticalFactType, AnalyticalRetentionPolicy> policies)
            throws Exception {

        try {
            return Class.forName(
                            CATALOG_TYPE)
                    .getConstructor(
                            Map.class)
                    .newInstance(
                            policies);

        } catch (InvocationTargetException invocation) {

            if (invocation.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }

            throw invocation;
        }
    }

    private static Object policyFor(
            Object catalog,
            AnalyticalFactType factType)
            throws Exception {

        return catalog.getClass()
                .getMethod(
                        "policyFor",
                        AnalyticalFactType.class)
                .invoke(
                        catalog,
                        factType);
    }

    @Test
    void requiresExplicitFiniteRetentionPolicyForEveryFactType() {
        // Why: an analytical fact schema whose retention nobody declared would
        // be retained by accident rather than by decision, and a later fact
        // type could be added with no policy at all.
        // Covers: acceptance of a complete catalog, exact resolution for every
        // declared fact type, and rejection when any existing fact type is
        // missing or maps to no policy.
        // Prevents: a silent default or fallback retention, and a fact type
        // whose retention was simply forgotten.
        //
        // Coverage is asserted dynamically over AnalyticalFactType.values(),
        // so introducing a new fact type later re-arms this regression without
        // editing the test. The catalog type is resolved reflectively so the
        // contract can be stated before production exists, keeping the failure
        // semantic rather than a test-compilation error.

        org.junit.jupiter.api.Assertions.assertAll(
                () -> assertThatCode(() ->
                        newCatalog(
                                completeCatalog()))
                        .as("A catalog covering every fact type must be"
                                + " accepted")
                        .doesNotThrowAnyException(),

                () -> {
                    var catalog =
                            newCatalog(
                                    completeCatalog());

                    for (var factType : AnalyticalFactType.values()) {

                        assertThat(
                                policyFor(
                                        catalog,
                                        factType))
                                .as("Fact type %s must resolve to exactly the"
                                        + " supplied policy", factType)
                                .isEqualTo(
                                        FIXTURE_POLICY);
                    }
                },

                () -> {
                    for (var omitted : AnalyticalFactType.values()) {

                        var incomplete =
                                completeCatalog();

                        incomplete.remove(
                                omitted);

                        assertThatThrownBy(() ->
                                newCatalog(
                                        incomplete))
                                .as("A catalog omitting fact type %s must be"
                                        + " rejected", omitted)
                                .isInstanceOf(
                                        IllegalArgumentException.class);
                    }
                },

                () -> {
                    for (var unmapped : AnalyticalFactType.values()) {

                        var withNullPolicy =
                                completeCatalog();

                        withNullPolicy.put(
                                unmapped,
                                null);

                        assertThatThrownBy(() ->
                                newCatalog(
                                        withNullPolicy))
                                .as("Fact type %s mapped to no policy must be"
                                        + " rejected", unmapped)
                                .isInstanceOf(
                                        IllegalArgumentException.class);
                    }
                });
    }
}
