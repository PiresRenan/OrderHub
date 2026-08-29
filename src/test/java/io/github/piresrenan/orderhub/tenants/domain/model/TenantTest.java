package io.github.piresrenan.orderhub.tenants.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.Test;

class TenantTest {

        private static final int MAX_NAME_CODE_POINTS = 120;

        @Test
        void createsTenantWithNormalizedName() {
                // Why: canonical domain state must not preserve meaningless surrounding
                // whitespace supplied by callers.
                // Covers: Tenant creation and name normalization.
                // Prevents: logically equivalent tenant names entering the domain with
                // inconsistent surrounding whitespace.

                var id = UUID.randomUUID();

                var tenant = Tenant.create(
                                id,
                                "  Acme Commerce  ");

                assertThat(tenant.id())
                                .isEqualTo(id);

                assertThat(tenant.name())
                                .isEqualTo("Acme Commerce");
        }

        @Test
        void rejectsMissingTenantId() {
                // Why: a Tenant without identity cannot be referenced or persisted safely.
                // Covers: aggregate identity invariant.
                // Prevents: null persistence keys and ambiguous tenant ownership.

                assertThatThrownBy(() -> Tenant.create(
                                null,
                                "Acme Commerce"))
                                .isInstanceOf(IllegalArgumentException.class)
                                .hasMessage("Tenant id is required");
        }

        @Test
        void rejectsMissingTenantName() {
                // Why: the initial Tenant model requires one human-readable domain name.
                // Covers: mandatory Tenant name invariant.
                // Prevents: incomplete aggregates entering application and persistence layers.

                assertThatThrownBy(() -> Tenant.create(
                                UUID.randomUUID(),
                                null))
                                .isInstanceOf(IllegalArgumentException.class)
                                .hasMessage("Tenant name is required");
        }

        @Test
        void rejectsBlankTenantName() {
                // Why: normalization can turn apparently populated input into empty domain
                // state.
                // Covers: blank-name validation after whitespace normalization.
                // Prevents: whitespace-only Tenant names being persisted as meaningful data.

                assertThatThrownBy(() -> Tenant.create(
                                UUID.randomUUID(),
                                "   "))
                                .isInstanceOf(IllegalArgumentException.class)
                                .hasMessage("Tenant name must not be blank");
        }

        @Test
        void acceptsTenantNameAtMaximumLength() {
                // Why: boundary behavior must be explicit rather than relying only on an
                // over-limit rejection test.
                // Covers: inclusive 120-code-point Tenant name boundary.
                // Prevents: off-by-one validation that rejects valid maximum-length names.

                var name = "a".repeat(MAX_NAME_CODE_POINTS);

                var tenant = Tenant.create(
                                UUID.randomUUID(),
                                name);

                assertThat(tenant.name())
                                .isEqualTo(name);
        }

        @Test
        void rejectsTenantNameAboveMaximumLength() {
                // Why: Tenant names require one deterministic bounded representation across
                // domain and persistence.
                // Covers: upper Tenant name boundary.
                // Prevents: unbounded descriptive state and disagreement with database
                // constraints.

                var name = "a".repeat(MAX_NAME_CODE_POINTS + 1);

                assertThatThrownBy(() -> Tenant.create(
                                UUID.randomUUID(),
                                name))
                                .isInstanceOf(IllegalArgumentException.class)
                                .hasMessage("Tenant name must not exceed 120 characters");
        }

        @Test
        void measuresTenantNameLimitByUnicodeCodePoint() {
                // Why: String.length() counts UTF-16 code units rather than Unicode code
                // points and can disagree with PostgreSQL character semantics.
                // Covers: non-BMP Unicode input at the exact domain boundary.
                // Prevents: valid international names being rejected earlier by Java than by
                // the relational constraint.

                var name = "😀".repeat(MAX_NAME_CODE_POINTS);

                var tenant = Tenant.create(
                                UUID.randomUUID(),
                                name);

                assertThat(tenant.name())
                                .isEqualTo(name);
        }

        @Test
        void rehydratesPersistedTenantState() {
                // Why: persistence reconstruction should be explicit rather than relying on
                // reflection or persistence-specific mutation of private domain state.
                // Covers: Tenant reconstruction from durable state.
                // Prevents: infrastructure concerns leaking into the aggregate.

                var id = UUID.randomUUID();

                var tenant = Tenant.rehydrate(
                                id,
                                "Acme Commerce");

                assertThat(tenant.id())
                                .isEqualTo(id);

                assertThat(tenant.name())
                                .isEqualTo("Acme Commerce");
        }

        @Test
        void rehydrationRejectsInvalidPersistedState() {
                // Why: persistence reconstruction must not bypass domain invariants.
                // Covers: structural validation during Tenant rehydration.
                // Prevents: corrupted database state silently becoming a valid domain
                // aggregate.

                assertThatThrownBy(() -> Tenant.rehydrate(
                                UUID.randomUUID(),
                                "   "))
                                .isInstanceOf(IllegalArgumentException.class)
                                .hasMessage("Tenant name must not be blank");
        }

        @Test
        void rehydrationRejectsNonNormalizedPersistedName() {
                // Why: persisted state should already satisfy the canonical representation
                // required by the aggregate.
                // Covers: normalization invariant during persistence reconstruction.
                // Prevents: rehydration silently hiding malformed durable state by modifying it
                // while loading.

                assertThatThrownBy(() -> Tenant.rehydrate(
                                UUID.randomUUID(),
                                "  Acme Commerce  "))
                                .isInstanceOf(IllegalArgumentException.class)
                                .hasMessage("Persisted tenant name must be normalized");
        }
}
