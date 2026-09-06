package io.github.piresrenan.orderhub.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

import io.github.piresrenan.orderhub.OrderHubApplication;

class AnalyticsModuleContractTest {

    /**
     * Verifies that analytics exists as its own application module rather than
     * as additional behaviour inside an operational module.
     */
    @Test
    void analyticsIsDetectedAsAnIndependentApplicationModule() {
        // Why: analytics must never become a second responsibility of an
        // operational module that owns transactional truth.
        // Covers: explicit discovery and distinct identity of the analytics
        // module root against workforce.
        // Prevents: analytical concerns being folded into workforce and
        // silently gaining access to workforce persistence.

        var modules =
                ApplicationModules.of(
                        OrderHubApplication.class);

        var analytics =
                modules.getModuleByName(
                        "analytics");

        var workforce =
                modules.getModuleByName(
                        "workforce");

        assertThat(analytics)
                .as("Analytics must be detected as an application module")
                .isPresent();

        assertThat(workforce)
                .as("Workforce must remain a distinct application module")
                .isPresent();

        assertThat(
                analytics.orElseThrow())
                .isNotSameAs(
                        workforce.orElseThrow());
    }

    /**
     * Verifies that introducing analytics does not create a cycle or an
     * operational dependency on analytics.
     */
    @Test
    void introducingAnalyticsPreservesApplicationModuleBoundaries() {
        // Why: an analytics module is only safe while operational modules do
        // not depend on it.
        // Covers: acyclic module dependencies and legal module access across
        // the whole application after analytics exists.
        // Prevents: analytics becoming a participant in operational
        // correctness through an inverted dependency edge.

        ApplicationModules
                .of(OrderHubApplication.class)
                .verify();
    }
}
