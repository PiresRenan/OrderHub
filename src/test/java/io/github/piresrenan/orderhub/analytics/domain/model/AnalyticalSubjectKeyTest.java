package io.github.piresrenan.orderhub.analytics.domain.model;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class AnalyticalSubjectKeyTest {

    @Test
    void rejectsMissingAnalyticalSubjectKeyValue() {
        // Why: a subject key without an internal value is an analytical
        // identity that cannot correlate anything, and it would otherwise
        // satisfy every contract that merely requires a non-null key
        // reference.
        // Covers: the internal value of the pseudonymous key itself.
        // Prevents: a valueless key passing the fact-level subject checks and
        // reaching analytical storage as an unusable identity.

        assertThatThrownBy(() ->
                new AnalyticalSubjectKey(
                        null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
