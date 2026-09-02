package io.github.piresrenan.orderhub.authorization.adapter.out.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import io.github.piresrenan.orderhub.authorization.application.observability.AuthorizationDecisionObservation;
import io.github.piresrenan.orderhub.authorization.application.observability.AuthorizationDecisionReason;
import io.github.piresrenan.orderhub.authorization.domain.model.AuthorizationDecision;
import io.github.piresrenan.orderhub.authorization.domain.model.AuthorizationPersona;
import io.github.piresrenan.orderhub.authorization.domain.model.PermissionCode;

class MicrometerAuthorizationDecisionObserverTest {

    @Test
    void recordsOneBoundedAuthorizationDecisionCounter() {

        var registry =
                new SimpleMeterRegistry();

        var observer =
                new MicrometerAuthorizationDecisionObserver(
                        registry);

        observer.observe(
                new AuthorizationDecisionObservation(
                        AuthorizationDecision.DENY,
                        AuthorizationPersona.STAFF,
                        PermissionCode.INVENTORY_ADJUST,
                        AuthorizationDecisionReason.POLICY_DENIED));

        var counter =
                registry.get(
                                MicrometerAuthorizationDecisionObserver.METRIC_NAME)
                        .tag(
                                "decision",
                                "DENY")
                        .tag(
                                "persona",
                                "STAFF")
                        .tag(
                                "permission",
                                "INVENTORY_ADJUST")
                        .tag(
                                "reason",
                                "POLICY_DENIED")
                        .counter();

        assertThat(
                counter.count())
                .isEqualTo(
                        1.0);
    }

    @Test
    void metricTagKeysAreExactlyTheBoundedPolicyVocabulary() {

        var registry =
                new SimpleMeterRegistry();

        var observer =
                new MicrometerAuthorizationDecisionObserver(
                        registry);

        observer.observe(
                new AuthorizationDecisionObservation(
                        AuthorizationDecision.ALLOW,
                        AuthorizationPersona.STAFF,
                        PermissionCode.ORDERS_VIEW,
                        AuthorizationDecisionReason.ELIGIBLE));

        var meter =
                registry.getMeters()
                        .getFirst();

        var tagKeys =
                meter.getId()
                        .getTags()
                        .stream()
                        .map(tag ->
                                tag.getKey())
                        .collect(
                                Collectors.toSet());

        assertThat(tagKeys)
                .isEqualTo(
                        Set.of(
                                "decision",
                                "persona",
                                "permission",
                                "reason"));
    }

    @Test
    void observationModelCarriesOnlyBoundedEnumDimensions() {

        var components =
                AuthorizationDecisionObservation.class
                        .getRecordComponents();

        var componentNames =
                Arrays.stream(
                                components)
                        .map(component ->
                                component.getName())
                        .collect(
                                Collectors.toSet());

        var componentTypeNames =
                Arrays.stream(
                                components)
                        .map(component ->
                                component.getType()
                                        .getName())
                        .collect(
                                Collectors.toSet());

        assertThat(componentNames)
                .containsExactlyInAnyOrder(
                        "decision",
                        "persona",
                        "permission",
                        "reason")
                .doesNotContain(
                        "userId",
                        "tenantId",
                        "resourceId",
                        "issuer",
                        "subject");

        assertThat(componentTypeNames)
                .containsExactlyInAnyOrder(
                        AuthorizationDecision.class.getName(),
                        AuthorizationPersona.class.getName(),
                        PermissionCode.class.getName(),
                        AuthorizationDecisionReason.class.getName())
                .doesNotContain(
                        String.class.getName(),
                        UUID.class.getName());
    }
}
