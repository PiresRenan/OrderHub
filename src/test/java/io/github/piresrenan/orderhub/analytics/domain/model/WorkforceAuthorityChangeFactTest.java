package io.github.piresrenan.orderhub.analytics.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class WorkforceAuthorityChangeFactTest {

    private static final UUID SOURCE_EVENT_ID =
            UUID.fromString("00000000-0000-4000-8000-000000000001");

    private static final UUID TENANT_ID =
            UUID.fromString("00000000-0000-4000-8000-000000000002");

    private static final AnalyticalSubjectKey ACTOR =
            new AnalyticalSubjectKey(
                    UUID.fromString("00000000-0000-4000-8000-000000000003"));

    private static final AnalyticalSubjectKey AFFECTED =
            new AnalyticalSubjectKey(
                    UUID.fromString("00000000-0000-4000-8000-000000000004"));

    private static final Instant OCCURRED_AT =
            Instant.parse("2026-01-01T00:00:00Z");

    private static WorkforceAuthorityChangeFact fact(
            String reasonCode) {

        return new WorkforceAuthorityChangeFact(
                SOURCE_EVENT_ID,
                TENANT_ID,
                ACTOR,
                AFFECTED,
                WorkforceAuthorityChangeAction.POSITION_AUTHORITY_CHANGED,
                WorkforceAuthorityChangeOutcome.APPLIED,
                reasonCode,
                OCCURRED_AT);
    }

    @Test
    void rejectsAFactWithoutExplicitTenantScope() {
        // Why: an analytical fact without Tenant scope cannot be constrained,
        // retained or deleted per Tenant.
        // Covers: mandatory Tenant scope on every analytical fact.
        // Prevents: Tenant-ambiguous analytical data accumulating silently.

        assertThatThrownBy(() ->
                new WorkforceAuthorityChangeFact(
                        SOURCE_EVENT_ID,
                        null,
                        ACTOR,
                        AFFECTED,
                        WorkforceAuthorityChangeAction.POSITION_CHANGED,
                        WorkforceAuthorityChangeOutcome.APPLIED,
                        null,
                        OCCURRED_AT))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsAFactWithoutTheSourceEventIdentityUsedForDeduplication() {
        // Why: the source event identity is the only idempotency key available
        // for at-least-once ingestion.
        // Covers: mandatory source event identity.
        // Prevents: a fact that cannot be deduplicated on re-ingestion.

        assertThatThrownBy(() ->
                new WorkforceAuthorityChangeFact(
                        null,
                        TENANT_ID,
                        ACTOR,
                        AFFECTED,
                        WorkforceAuthorityChangeAction.POSITION_CHANGED,
                        WorkforceAuthorityChangeOutcome.APPLIED,
                        null,
                        OCCURRED_AT))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsAFactWithoutBothAnalyticalSubjects() {
        // Why: subject correlation is the analytical purpose; an absent subject
        // makes the fact uncorrelatable rather than merely anonymous.
        // Covers: mandatory actor and affected analytical subject keys.
        // Prevents: partially identified facts entering analytical storage.

        assertThatThrownBy(() ->
                new WorkforceAuthorityChangeFact(
                        SOURCE_EVENT_ID,
                        TENANT_ID,
                        null,
                        AFFECTED,
                        WorkforceAuthorityChangeAction.POSITION_CHANGED,
                        WorkforceAuthorityChangeOutcome.APPLIED,
                        null,
                        OCCURRED_AT))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() ->
                new WorkforceAuthorityChangeFact(
                        SOURCE_EVENT_ID,
                        TENANT_ID,
                        ACTOR,
                        null,
                        WorkforceAuthorityChangeAction.POSITION_CHANGED,
                        WorkforceAuthorityChangeOutcome.APPLIED,
                        null,
                        OCCURRED_AT))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsAFactWithoutBoundedActionOutcomeAndOccurrenceTime() {
        // Why: an analytical fact must describe a closed vocabulary of what
        // happened and when.
        // Covers: mandatory action, outcome and occurrence time.
        // Prevents: unclassified analytical rows that cannot be aggregated.

        assertThatThrownBy(() ->
                new WorkforceAuthorityChangeFact(
                        SOURCE_EVENT_ID,
                        TENANT_ID,
                        ACTOR,
                        AFFECTED,
                        null,
                        WorkforceAuthorityChangeOutcome.APPLIED,
                        null,
                        OCCURRED_AT))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() ->
                new WorkforceAuthorityChangeFact(
                        SOURCE_EVENT_ID,
                        TENANT_ID,
                        ACTOR,
                        AFFECTED,
                        WorkforceAuthorityChangeAction.POSITION_CHANGED,
                        null,
                        null,
                        OCCURRED_AT))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() ->
                new WorkforceAuthorityChangeFact(
                        SOURCE_EVENT_ID,
                        TENANT_ID,
                        ACTOR,
                        AFFECTED,
                        WorkforceAuthorityChangeAction.POSITION_CHANGED,
                        WorkforceAuthorityChangeOutcome.APPLIED,
                        null,
                        null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void restrictsTheReasonCodeToABoundedVocabulary() {
        // Why: a free-form reason string is exactly the arbitrary payload that
        // privacy-safe analytics must not accumulate.
        // Covers: bounded uppercase reason vocabulary and the absent-reason
        // case.
        // Prevents: operator prose, identifiers or personal data entering
        // analytical storage through a nominally bounded field.

        assertThatCode(() ->
                fact("PRIVILEGED_POLICY_DENIED"))
                .doesNotThrowAnyException();

        assertThatCode(() ->
                fact(null))
                .doesNotThrowAnyException();

        assertThatThrownBy(() ->
                fact("customer complained about renan@example.com"))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() ->
                fact("lowercase_reason"))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() ->
                fact("AB"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void carriesNoOperationalSubjectIdentifierAndNoUnboundedField() {
        // Why: data minimization must be structurally enforced, not merely
        // documented, so a later change cannot quietly reintroduce an
        // operational identifier.
        // Covers: every record component type; only the source event identity
        // and Tenant scope may be raw UUIDs, subjects must be pseudonymous,
        // and the reason code must be the only String.
        // Prevents: staffId, userId, correlationId, email, display name, raw
        // tokens or a free-form payload becoming analytical columns.

        var components =
                WorkforceAuthorityChangeFact.class
                        .getRecordComponents();

        assertThat(components)
                .as("Analytical fact must expose a closed set of components")
                .hasSize(10);

        var rawUuidComponents =
                java.util.Arrays.stream(components)
                        .filter(component ->
                                component.getType() == UUID.class)
                        .map(java.lang.reflect.RecordComponent::getName)
                        .toList();

        assertThat(rawUuidComponents)
                .as("Only source event identity and Tenant scope may be raw"
                        + " operational UUIDs")
                .containsExactlyInAnyOrder(
                        "sourceEventId",
                        "tenantId");

        var stringComponents =
                java.util.Arrays.stream(components)
                        .filter(component ->
                                component.getType() == String.class)
                        .map(java.lang.reflect.RecordComponent::getName)
                        .toList();

        assertThat(stringComponents)
                .as("The bounded reason code must be the only textual"
                        + " component")
                .containsExactly(
                        "reasonCode");

        assertThat(
                java.util.Arrays.stream(components)
                        .map(component ->
                                component.getType()
                                        .getSimpleName())
                        .toList())
                .as("No map, collection or free-form payload component is"
                        + " permitted")
                .doesNotContain(
                        "Map",
                        "List",
                        "Set",
                        "Object",
                        "byte[]");
    }

    @Test
    void declaresBoundedFactTypeAndSchemaVersion() {
        // Why: a persisted analytical row must remain interpretable and
        // evolvable after the code that wrote it has changed, which requires
        // the fact to name its own bounded type and its schema version.
        // Covers: presence of factType and schemaVersion, and their structural
        // representation, inspected reflectively so the contract can be stated
        // before the production type exists.
        // Prevents: an unbounded String fact type, a free-form textual version
        // such as "v1.2.3-beta", and an unversioned persisted fact.

        var componentTypes =
                java.util.Arrays.stream(
                                WorkforceAuthorityChangeFact.class
                                        .getRecordComponents())
                        .collect(
                                java.util.stream.Collectors.toMap(
                                        java.lang.reflect.RecordComponent::getName,
                                        java.lang.reflect.RecordComponent::getType));

        assertThat(componentTypes)
                .as("Analytical schema identity must belong to the fact"
                        + " contract")
                .containsKeys(
                        "factType",
                        "schemaVersion");

        assertThat(componentTypes.get("factType"))
                .as("Fact type must be a bounded vocabulary, never arbitrary"
                        + " text")
                .isNotEqualTo(String.class)
                .matches(
                        Class::isEnum,
                        "a bounded enum type");

        assertThat(componentTypes.get("schemaVersion"))
                .as("Schema version must be numeric, never free-form text")
                .isNotEqualTo(String.class)
                .isIn(
                        int.class,
                        Integer.class);
    }

    @Test
    void rejectsInvalidAnalyticalSchemaIdentity() {
        // Why: a bounded type and a numeric version are only useful if the
        // values are actually valid; a null type or a non-positive version
        // would persist a row that names no schema it can be read back with.
        // Covers: acceptance of explicit valid schema identity, and rejection
        // of an absent fact type, a zero version and a negative version.
        // Prevents: an unidentifiable or unversioned analytical row entering
        // storage through the canonical constructor.
        //
        // The assertions are aggregated so one execution proves every gap
        // rather than stopping at the first missing guard. A positive version
        // other than 1 is deliberately not constrained here: the invariant is
        // positivity, not a supported-version policy.

        org.junit.jupiter.api.Assertions.assertAll(
                () -> assertThatCode(() ->
                        new WorkforceAuthorityChangeFact(
                                SOURCE_EVENT_ID,
                                TENANT_ID,
                                ACTOR,
                                AFFECTED,
                                WorkforceAuthorityChangeAction
                                        .POSITION_AUTHORITY_CHANGED,
                                WorkforceAuthorityChangeOutcome.APPLIED,
                                null,
                                OCCURRED_AT,
                                AnalyticalFactType
                                        .WORKFORCE_AUTHORITY_CHANGE,
                                1))
                        .as("Explicit valid schema identity must be accepted")
                        .doesNotThrowAnyException(),

                () -> assertThatThrownBy(() ->
                        new WorkforceAuthorityChangeFact(
                                SOURCE_EVENT_ID,
                                TENANT_ID,
                                ACTOR,
                                AFFECTED,
                                WorkforceAuthorityChangeAction
                                        .POSITION_AUTHORITY_CHANGED,
                                WorkforceAuthorityChangeOutcome.APPLIED,
                                null,
                                OCCURRED_AT,
                                null,
                                1))
                        .as("An absent fact type must be rejected")
                        .isInstanceOf(
                                IllegalArgumentException.class),

                () -> assertThatThrownBy(() ->
                        new WorkforceAuthorityChangeFact(
                                SOURCE_EVENT_ID,
                                TENANT_ID,
                                ACTOR,
                                AFFECTED,
                                WorkforceAuthorityChangeAction
                                        .POSITION_AUTHORITY_CHANGED,
                                WorkforceAuthorityChangeOutcome.APPLIED,
                                null,
                                OCCURRED_AT,
                                AnalyticalFactType
                                        .WORKFORCE_AUTHORITY_CHANGE,
                                0))
                        .as("A zero schema version must be rejected")
                        .isInstanceOf(
                                IllegalArgumentException.class),

                () -> assertThatThrownBy(() ->
                        new WorkforceAuthorityChangeFact(
                                SOURCE_EVENT_ID,
                                TENANT_ID,
                                ACTOR,
                                AFFECTED,
                                WorkforceAuthorityChangeAction
                                        .POSITION_AUTHORITY_CHANGED,
                                WorkforceAuthorityChangeOutcome.APPLIED,
                                null,
                                OCCURRED_AT,
                                AnalyticalFactType
                                        .WORKFORCE_AUTHORITY_CHANGE,
                                -1))
                        .as("A negative schema version must be rejected")
                        .isInstanceOf(
                                IllegalArgumentException.class));
    }
}
