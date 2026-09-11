package io.github.piresrenan.orderhub.users.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import io.github.piresrenan.orderhub.users.application.port.in.IsTenantMembershipOperationallyActiveQuery;
import io.github.piresrenan.orderhub.users.application.port.out.TenantMembershipRepository;
import io.github.piresrenan.orderhub.users.domain.model.TenantMembership;
import io.github.piresrenan.orderhub.users.domain.model.TenantMembershipStatus;

class IsTenantMembershipOperationallyActiveServiceTest {

    @Test
    void reportsActiveMembershipAsOperationalForTheExactUserTenantPair() {
        // Why: Users owns the question of whether one exact relationship may still
        // participate in operations that require proven membership.
        // Covers: delegation of both identity components plus the real domain
        // lifecycle evaluation of an ACTIVE membership.
        // Prevents: consumers reimplementing membership lifecycle policy or
        // depending on the Users domain model to answer it.

        var userId = UUID.randomUUID();
        var tenantId = UUID.randomUUID();

        var repository = new RecordingTenantMembershipRepository(
                Optional.of(
                        TenantMembership.rehydrate(
                                userId,
                                tenantId,
                                TenantMembershipStatus.ACTIVE)));

        var service = new IsTenantMembershipOperationallyActiveService(
                repository);

        var operational = service.isOperationallyActive(
                new IsTenantMembershipOperationallyActiveQuery(
                        userId,
                        tenantId));

        assertThat(operational)
                .isTrue();

        assertThat(repository.receivedUserId)
                .isEqualTo(userId);

        assertThat(repository.receivedTenantId)
                .isEqualTo(tenantId);

        assertThat(repository.findCount)
                .isEqualTo(1);
    }

    @Test
    void reportsMissingMembershipAsNonOperational() {
        // Why: absence of the exact relationship must fail closed instead of
        // becoming an error or an implicit allowance.
        // Covers: an empty persistence result answered as a negative predicate.
        // Prevents: authentication alone being treated as membership.

        var repository = new RecordingTenantMembershipRepository(
                Optional.empty());

        var service = new IsTenantMembershipOperationallyActiveService(
                repository);

        var operational = service.isOperationallyActive(
                new IsTenantMembershipOperationallyActiveQuery(
                        UUID.randomUUID(),
                        UUID.randomUUID()));

        assertThat(operational)
                .isFalse();

        assertThat(repository.findCount)
                .isEqualTo(1);
    }

    @ParameterizedTest
    @EnumSource(
            value = TenantMembershipStatus.class,
            names = "ACTIVE",
            mode = EnumSource.Mode.EXCLUDE)
    void reportsEveryNonActiveLifecycleStateAsNonOperational(
            TenantMembershipStatus nonOperationalStatus) {
        // Why: a preserved but non-operational relationship must stop being
        // eligible while the eligible vocabulary stays owned by Users.
        // Covers: every persisted lifecycle state other than ACTIVE evaluated
        // through the real TenantMembership domain behavior.
        // Prevents: a newly added lifecycle state silently defaulting to eligible,
        // or the vocabulary being duplicated by consuming modules.

        var userId = UUID.randomUUID();
        var tenantId = UUID.randomUUID();

        var repository = new RecordingTenantMembershipRepository(
                Optional.of(
                        TenantMembership.rehydrate(
                                userId,
                                tenantId,
                                nonOperationalStatus)));

        var service = new IsTenantMembershipOperationallyActiveService(
                repository);

        assertThat(
                service.isOperationallyActive(
                        new IsTenantMembershipOperationallyActiveQuery(
                                userId,
                                tenantId)))
                .isFalse();
    }

    @Test
    void rejectsQueryWithoutUserIdBeforeRepositoryAccess() {
        // Why: an incomplete membership identity must not reach persistence.
        // Covers: required User identity at the application query boundary.
        // Prevents: invalid queries being silently answered as non-membership.

        var repository = new RecordingTenantMembershipRepository(
                Optional.empty());

        var service = new IsTenantMembershipOperationallyActiveService(
                repository);

        assertThatThrownBy(() ->
                service.isOperationallyActive(
                        new IsTenantMembershipOperationallyActiveQuery(
                                null,
                                UUID.randomUUID())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Membership user id is required");

        assertThat(repository.findCount)
                .isZero();
    }

    @Test
    void rejectsQueryWithoutTenantIdBeforeRepositoryAccess() {
        // Why: membership eligibility requires the complete User/Tenant pair.
        // Covers: required Tenant identity at the application query boundary.
        // Prevents: partial lookup semantics leaking into the repository.

        var repository = new RecordingTenantMembershipRepository(
                Optional.empty());

        var service = new IsTenantMembershipOperationallyActiveService(
                repository);

        assertThatThrownBy(() ->
                service.isOperationallyActive(
                        new IsTenantMembershipOperationallyActiveQuery(
                                UUID.randomUUID(),
                                null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Membership tenant id is required");

        assertThat(repository.findCount)
                .isZero();
    }

    private static final class RecordingTenantMembershipRepository
            implements TenantMembershipRepository {

        private final Optional<TenantMembership> result;
        private UUID receivedUserId;
        private UUID receivedTenantId;
        private int findCount;

        /**
         * Creates a focused repository double returning the configured lookup
         * result.
         *
         * @param result membership lookup result to expose
         */
        private RecordingTenantMembershipRepository(
                Optional<TenantMembership> result) {

            this.result = result;
        }

        /**
         * Satisfies the persistence contract without introducing write behavior
         * unrelated to membership eligibility.
         *
         * @param membership membership requested for persistence
         * @return never reached by these query-focused tests
         */
        @Override
        public TenantMembership save(
                TenantMembership membership) {

            throw new UnsupportedOperationException(
                    "Save is outside this test scope");
        }

        /**
         * Records the complete membership identity supplied by the application
         * service.
         *
         * @param userId internal User identifier
         * @param tenantId Tenant identifier
         * @return configured lookup result
         */
        @Override
        public Optional<TenantMembership> find(
                UUID userId,
                UUID tenantId) {

            this.receivedUserId = userId;
            this.receivedTenantId = tenantId;
            this.findCount++;

            return result;
        }
    }
}
