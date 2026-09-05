package io.github.piresrenan.orderhub.analytics.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.lang.reflect.Method;
import java.util.Arrays;

import org.junit.jupiter.api.Test;

class AnalyticalFactTypeTest {

    private static final String CLASSIFICATION_TYPE =
            "io.github.piresrenan.orderhub.analytics.domain.model"
                    + ".AnalyticalDataClassification";

    @Test
    void classifiesWorkforceAuthorityChangeAsPseudonymous() {
        // Why: retention, allowed consumers and privacy review all depend on
        // knowing how sensitive a fact class is, and that must be declared by
        // the schema rather than inferred by a reader.
        // Covers: existence of a bounded analytics-owned classification
        // vocabulary, its exposure as fact-schema metadata, and the concrete
        // classification of the only fact type that currently exists.
        // Prevents: an unclassified analytical fact schema, and an arbitrary
        // String classification that no boundary could enforce.
        //
        // Classification is schema metadata, not caller-supplied row state, so
        // it is read from the fact type rather than from a fact component.
        // The type is resolved reflectively so this contract can be stated
        // before the production type exists, keeping the failure semantic.

        org.junit.jupiter.api.Assertions.assertAll(
                () -> assertThatCode(() -> {
                    var classificationType =
                            Class.forName(
                                    CLASSIFICATION_TYPE);

                    assertThat(classificationType.isEnum())
                            .as("Classification must be a bounded vocabulary,"
                                    + " never arbitrary text")
                            .isTrue();

                    assertThat(
                            Arrays.stream(
                                            classificationType
                                                    .getEnumConstants())
                                    .map(Object::toString))
                            .as("Only the classification required by a current"
                                    + " analytical fact may be declared")
                            .containsExactly(
                                    "PSEUDONYMOUS");
                }).doesNotThrowAnyException(),

                () -> assertThat(
                        Arrays.stream(
                                        AnalyticalFactType.class
                                                .getDeclaredMethods())
                                .map(Method::getName))
                        .as("Fact type must expose bounded classification"
                                + " metadata")
                        .contains(
                                "classification"),

                () -> assertThatCode(() -> {
                    var classificationType =
                            Class.forName(
                                    CLASSIFICATION_TYPE);

                    var classificationMethod =
                            AnalyticalFactType.class
                                    .getMethod(
                                            "classification");

                    assertThat(
                            classificationMethod
                                    .getReturnType())
                            .as("Classification metadata must be the bounded"
                                    + " type")
                            .isEqualTo(
                                    classificationType);

                    var classification =
                            classificationMethod.invoke(
                                    AnalyticalFactType
                                            .WORKFORCE_AUTHORITY_CHANGE);

                    assertThat(
                            classification.toString())
                            .as("The workforce authority change fact carries"
                                    + " pseudonymous subject identity")
                            .isEqualTo(
                                    "PSEUDONYMOUS");
                }).doesNotThrowAnyException());
    }
}
