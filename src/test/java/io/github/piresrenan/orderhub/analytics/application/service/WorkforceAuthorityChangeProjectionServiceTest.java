package io.github.piresrenan.orderhub.analytics.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.github.piresrenan.orderhub.analytics.application.port.out.AnalyticalSubjectPseudonymRepository;
import io.github.piresrenan.orderhub.analytics.application.port.out.WorkforceAuthorityChangeFactRepository;
import io.github.piresrenan.orderhub.analytics.domain.model.AnalyticalSubjectKey;
import io.github.piresrenan.orderhub.analytics.domain.model.AnalyticalFactType;
import io.github.piresrenan.orderhub.analytics.domain.model.AnalyticalRetentionPolicy;
import io.github.piresrenan.orderhub.analytics.domain.model.AnalyticalRetentionPolicyCatalog;
import io.github.piresrenan.orderhub.analytics.domain.model.WorkforceAuthorityChangeFact;
import io.github.piresrenan.orderhub.workforce.application.model.WorkforceAuditActionType;
import io.github.piresrenan.orderhub.workforce.application.model.WorkforceAuditOutcome;
import io.github.piresrenan.orderhub.workforce.application.port.in.ResolveWorkforceAuthorityChangeAnalyticsSourceUseCase;
import io.github.piresrenan.orderhub.workforce.application.port.in.WorkforceAuthorityChangeAnalyticsSource;

/**
 * Proves the lock-ordering invariant that keeps concurrent first-time
 * projections from deadlocking each other.
 *
 * <p>
 * Establishing a subject mapping locks its row until the surrounding
 * transaction resolves. The invariant is asserted directly rather than by
 * racing two transactions, because a deadlock reproduction would be
 * probabilistic while the property that prevents it — a single global
 * acquisition order — is exact.
 * </p>
 */
class WorkforceAuthorityChangeProjectionServiceTest {

    @Test
    void ignoresAnAlreadyExpiredReplayBeforePersistingAnything() {
        var tenantId = UUID.randomUUID();
        var eventId = UUID.randomUUID();
        var pseudonyms = new RecordingPseudonymRepository();
        var facts = new CapturingFactRepository();
        var sourceTime = Instant.parse("2026-01-01T00:00:00Z");
        var service = new WorkforceAuthorityChangeProjectionService(
                sourceOf(tenantId, UUID.randomUUID(), UUID.randomUUID()),
                pseudonyms,
                facts,
                new AnalyticalRetentionPolicyCatalog(Map.of(
                        AnalyticalFactType.WORKFORCE_AUTHORITY_CHANGE,
                        new AnalyticalRetentionPolicy(Duration.ofDays(30)))),
                Clock.fixed(sourceTime.plus(Duration.ofDays(30)),
                        ZoneOffset.UTC));

        assertThat(service.project(tenantId, eventId))
                .isEqualTo(WorkforceAuthorityChangeProjectionResult.IGNORED);
        assertThat(pseudonyms.resolutionOrder).isEmpty();
        assertThat(facts.captured).isNull();
    }

    @Test
    void resolvesBothSubjectsInOneGlobalOrderWhicheverActed() {
        // Why: two projections whose actor and affected subjects are reversed
        // would otherwise take the same two mapping rows in opposite orders. On
        // PostgreSQL that is a deadlock, one listener transaction is aborted,
        // and its fact then waits for the next restart to be recovered.
        // Covers: both projections visiting the operational identifiers in the
        // same order regardless of which subject acted, while still assigning
        // each analytical key to the correct role.
        // Prevents: an ordering that depends on the direction of the action.

        var tenantId = UUID.randomUUID();

        var lowerStaffId =
                UUID.fromString(
                        "00000000-0000-4000-8000-000000000001");

        var higherStaffId =
                UUID.fromString(
                        "ffffffff-0000-4000-8000-000000000002");

        var forward =
                project(
                        tenantId,
                        lowerStaffId,
                        higherStaffId);

        var reversed =
                project(
                        tenantId,
                        higherStaffId,
                        lowerStaffId);

        assertThat(forward.resolutionOrder())
                .as("Both mappings must be established")
                .containsExactlyInAnyOrder(
                        lowerStaffId,
                        higherStaffId);

        assertThat(reversed.resolutionOrder())
                .as("Reversing actor and affected must not reverse the order in"
                        + " which the mapping rows are locked. The specific"
                        + " order does not matter; that both directions agree"
                        + " on one order is exactly what breaks the deadlock"
                        + " cycle")
                .containsExactlyElementsOf(
                        forward.resolutionOrder());

        assertThat(forward.fact().actorSubject())
                .as("The acting subject must still receive its own key")
                .isEqualTo(
                        forward.keyFor(
                                lowerStaffId));

        assertThat(forward.fact().affectedSubject())
                .isEqualTo(
                        forward.keyFor(
                                higherStaffId));

        assertThat(reversed.fact().actorSubject())
                .as("Ordering is a locking detail and must not move a key onto"
                        + " the wrong role")
                .isEqualTo(
                        reversed.keyFor(
                                higherStaffId));

        assertThat(reversed.fact().affectedSubject())
                .isEqualTo(
                        reversed.keyFor(
                                lowerStaffId));
    }

    @Test
    void resolvesOneMappingWhenASubjectActedOnItself() {
        // Why: the ordering rule must not turn a self-directed action into two
        // resolutions of the same row.
        // Covers: a single mapping resolution when both roles are one subject.
        // Prevents: a redundant second lock acquisition on an identical row.

        var tenantId = UUID.randomUUID();
        var staffId = UUID.randomUUID();

        var projected =
                project(
                        tenantId,
                        staffId,
                        staffId);

        assertThat(projected.resolutionOrder())
                .containsExactly(staffId);

        assertThat(projected.fact().actorSubject())
                .isEqualTo(
                        projected.fact().affectedSubject());
    }

    private Projection project(
            UUID tenantId,
            UUID actorStaffId,
            UUID affectedStaffId) {

        var pseudonyms =
                new RecordingPseudonymRepository();

        var facts =
                new CapturingFactRepository();

        var service =
                new WorkforceAuthorityChangeProjectionService(
                        sourceOf(
                                tenantId,
                                actorStaffId,
                                affectedStaffId),
                        pseudonyms,
                        facts);

        var result =
                service.project(
                        tenantId,
                        UUID.randomUUID());

        assertThat(result)
                .isEqualTo(
                        WorkforceAuthorityChangeProjectionResult.PROJECTED);

        return new Projection(
                pseudonyms.resolutionOrder,
                pseudonyms.assigned,
                facts.captured);
    }

    private ResolveWorkforceAuthorityChangeAnalyticsSourceUseCase sourceOf(
            UUID tenantId,
            UUID actorStaffId,
            UUID affectedStaffId) {

        return (requestedTenantId, requestedAuditEventId) ->
                Optional.of(
                        new WorkforceAuthorityChangeAnalyticsSource(
                                tenantId,
                                requestedAuditEventId,
                                actorStaffId,
                                affectedStaffId,
                                WorkforceAuditActionType.PRIVILEGED_MUTATION,
                                WorkforceAuditOutcome.DENIED,
                                "PRIVILEGED_POLICY_DENIED",
                                Instant.parse(
                                        "2026-01-01T00:00:00Z")));
    }

    private record Projection(
            List<UUID> resolutionOrder,
            List<AnalyticalSubjectKey> assignedKeys,
            WorkforceAuthorityChangeFact fact) {

        AnalyticalSubjectKey keyFor(
                UUID staffId) {

            return assignedKeys.get(
                    resolutionOrder.indexOf(
                            staffId));
        }
    }

    private static final class RecordingPseudonymRepository
            implements AnalyticalSubjectPseudonymRepository {

        private final List<UUID> resolutionOrder =
                new ArrayList<>();

        private final List<AnalyticalSubjectKey> assigned =
                new ArrayList<>();

        @Override
        public AnalyticalSubjectKey resolveOrCreate(
                UUID tenantId,
                UUID operationalSubjectId) {

            var key =
                    new AnalyticalSubjectKey(
                            UUID.randomUUID());

            resolutionOrder.add(
                    operationalSubjectId);

            assigned.add(key);

            return key;
        }
    }

    private static final class CapturingFactRepository
            implements WorkforceAuthorityChangeFactRepository {

        private WorkforceAuthorityChangeFact captured;

        @Override
        public void append(
                WorkforceAuthorityChangeFact fact) {

            captured = fact;
        }
    }
}
