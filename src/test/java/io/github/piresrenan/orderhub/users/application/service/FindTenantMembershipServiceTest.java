package io.github.piresrenan.orderhub.users.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.github.piresrenan.orderhub.users.application.port.in.FindTenantMembershipQuery;
import io.github.piresrenan.orderhub.users.application.port.out.TenantMembershipRepository;
import io.github.piresrenan.orderhub.users.domain.model.TenantMembership;

class FindTenantMembershipServiceTest {

    @Test
    void findsMembershipByExactUserTenantPair() {
        // Why: application callers need a framework-neutral query boundary for one
        // exact User/Tenant association.
        // Covers: delegation of both identity components and propagation of the
        // reconstructed membership.
        // Prevents: callers depending directly on persistence repositories.

        var userId = UUID.randomUUID();
        var tenantId = UUID.randomUUID();

        var expected = TenantMembership.rehydrate(
                userId,
                tenantId);

        var repository = new RecordingTenantMembershipRepository(
                Optional.of(expected));

        var service = new FindTenantMembershipService(
                repository);

        var result = service.find(
                new FindTenantMembershipQuery(
                        userId,
                        tenantId));

        assertThat(result)
                .containsSame(expected);

        assertThat(repository.receivedUserId)
                .isEqualTo(userId);

        assertThat(repository.receivedTenantId)
                .isEqualTo(tenantId);

        assertThat(repository.findCount)
                .isEqualTo(1);
    }

    @Test
    void returnsEmptyWhenMembershipDoesNotExist() {
        // Why: absence of membership is a normal application query result.
        // Covers: propagation of Optional.empty() from the output boundary.
        // Prevents: absence being converted into an infrastructure or domain error.

        var repository = new RecordingTenantMembershipRepository(
                Optional.empty());

        var service = new FindTenantMembershipService(
                repository);

        var result = service.find(
                new FindTenantMembershipQuery(
                        UUID.randomUUID(),
                        UUID.randomUUID()));

        assertThat(result)
                .isEmpty();

        assertThat(repository.findCount)
                .isEqualTo(1);
    }

    @Test
    void rejectsQueryWithoutUserIdBeforeRepositoryAccess() {
        // Why: an incomplete membership identity must not reach persistence.
        // Covers: required User identity at the application query boundary.
        // Prevents: invalid queries being silently interpreted as "not found".

        var repository = new RecordingTenantMembershipRepository(
                Optional.empty());

        var service = new FindTenantMembershipService(
                repository);

        assertThatThrownBy(() ->
                service.find(
                        new FindTenantMembershipQuery(
                                null,
                                UUID.randomUUID())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Membership user id is required");

        assertThat(repository.findCount)
                .isZero();
    }

    @Test
    void rejectsQueryWithoutTenantIdBeforeRepositoryAccess() {
        // Why: membership lookup requires the complete User/Tenant identity pair.
        // Covers: required Tenant identity at the application query boundary.
        // Prevents: partial lookup semantics leaking into the repository.

        var repository = new RecordingTenantMembershipRepository(
                Optional.empty());

        var service = new FindTenantMembershipService(
                repository);

        assertThatThrownBy(() ->
                service.find(
                        new FindTenantMembershipQuery(
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
         * Creates a focused repository double returning the configured query result.
         *
         * @param result membership lookup result to expose
         */
        private RecordingTenantMembershipRepository(
                Optional<TenantMembership> result) {

            this.result = result;
        }

        /**
         * Satisfies the persistence contract without introducing write behavior
         * unrelated to membership querying.
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
         * query service.
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
