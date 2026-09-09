package io.github.piresrenan.orderhub.users.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.github.piresrenan.orderhub.users.application.port.in.EstablishTenantMembershipCommand;
import io.github.piresrenan.orderhub.users.application.port.in.TenantMembershipEnsureResult;
import io.github.piresrenan.orderhub.users.application.port.out.TenantMembershipAlreadyExistsException;
import io.github.piresrenan.orderhub.users.application.port.out.TenantMembershipPersistenceException;
import io.github.piresrenan.orderhub.users.application.port.out.TenantMembershipRepository;
import io.github.piresrenan.orderhub.users.domain.model.TenantMembership;
import io.github.piresrenan.orderhub.users.domain.model.TenantMembershipStatus;

class EnsureActiveTenantMembershipServiceConcurrencyTest {

    private static final UUID USER_ID =
            UUID.fromString(
                    "51000000-0000-4000-8000-000000000001");

    private static final UUID TENANT_ID =
            UUID.fromString(
                    "51000000-0000-4000-8000-000000000002");

    @Test
    void duplicateInsertRaceThatSettlesActiveIsDesiredStateSuccess() {

        var repository =
                duplicateCollisionWith(
                        TenantMembershipStatus.ACTIVE);

        var result =
                invokeWithoutDuplicateLeak(
                        repository);

        assertThat(result)
                .isInstanceOf(
                        TenantMembershipEnsureResult.Operational.class);

        assertThat(repository.findCount())
                .isEqualTo(
                        2);

        assertThat(repository.saveCount())
                .isEqualTo(
                        1);
    }

    @Test
    void duplicateInsertRaceThatSettlesSuspendedRemainsNonOperational() {

        var repository =
                duplicateCollisionWith(
                        TenantMembershipStatus.SUSPENDED);

        var result =
                invokeWithoutDuplicateLeak(
                        repository);

        assertThat(result)
                .isInstanceOf(
                        TenantMembershipEnsureResult.NonOperational.class);

        assertThat(repository.findCount())
                .isEqualTo(
                        2);

        assertThat(repository.saveCount())
                .isEqualTo(
                        1);
    }

    @Test
    void duplicateInsertRaceThatSettlesTerminatedRemainsNonOperational() {

        var repository =
                duplicateCollisionWith(
                        TenantMembershipStatus.TERMINATED);

        var result =
                invokeWithoutDuplicateLeak(
                        repository);

        assertThat(result)
                .isInstanceOf(
                        TenantMembershipEnsureResult.NonOperational.class);

        assertThat(repository.findCount())
                .isEqualTo(
                        2);

        assertThat(repository.saveCount())
                .isEqualTo(
                        1);
    }

    @Test
    void duplicateCollisionThatCannotBeReconciledReReadsThenFailsClosed() {

        var duplicate =
                new TenantMembershipAlreadyExistsException();

        var repository =
                new CollisionRepository(
                        duplicate,
                        null);

        assertThatThrownBy(
                () -> service(
                        repository)
                        .ensureActive(
                                command()))
                .isSameAs(
                        duplicate);

        if (repository.findCount() != 2) {

            throw new AssertionError(
                    "Concurrent duplicate membership must be re-read before fail-closed");
        }

        assertThat(repository.saveCount())
                .isEqualTo(
                        1);
    }

    @Test
    void unrelatedPersistenceFailurePropagatesWithoutCollisionRecoveryRead() {

        var failure =
                new TenantMembershipPersistenceException(
                        new IllegalStateException(
                                "synthetic persistence failure"));

        var repository =
                new CollisionRepository(
                        failure,
                        TenantMembership.rehydrate(
                                USER_ID,
                                TENANT_ID,
                                TenantMembershipStatus.ACTIVE));

        assertThatThrownBy(
                () -> service(
                        repository)
                        .ensureActive(
                                command()))
                .isSameAs(
                        failure);

        assertThat(repository.findCount())
                .isEqualTo(
                        1);

        assertThat(repository.saveCount())
                .isEqualTo(
                        1);
    }

    private CollisionRepository duplicateCollisionWith(
            TenantMembershipStatus durableStatus) {

        return new CollisionRepository(
                new TenantMembershipAlreadyExistsException(),
                TenantMembership.rehydrate(
                        USER_ID,
                        TENANT_ID,
                        durableStatus));
    }

    private TenantMembershipEnsureResult invokeWithoutDuplicateLeak(
            CollisionRepository repository) {

        try {

            return service(
                    repository)
                    .ensureActive(
                            command());

        } catch (TenantMembershipAlreadyExistsException exception) {

            throw new AssertionError(
                    "Concurrent duplicate membership must be reconciled from durable state",
                    exception);
        }
    }

    private EnsureActiveTenantMembershipService service(
            TenantMembershipRepository repository) {

        return new EnsureActiveTenantMembershipService(
                repository);
    }

    private EstablishTenantMembershipCommand command() {

        return new EstablishTenantMembershipCommand(
                USER_ID,
                TENANT_ID);
    }

    /**
     * Deterministically reproduces the persistence interleaving:
     *
     * <pre>
     * caller B find -> absent
     * caller A establishes durable state
     * caller B save -> duplicate
     * caller B re-read -> caller A state
     * </pre>
     */
    private static final class CollisionRepository
            implements TenantMembershipRepository {

        private final RuntimeException saveFailure;

        private final TenantMembership durableAfterCollision;

        private int findCount;

        private int saveCount;

        private CollisionRepository(
                RuntimeException saveFailure,
                TenantMembership durableAfterCollision) {

            this.saveFailure =
                    saveFailure;

            this.durableAfterCollision =
                    durableAfterCollision;
        }

        @Override
        public TenantMembership save(
                TenantMembership membership) {

            saveCount++;

            throw saveFailure;
        }

        @Override
        public Optional<TenantMembership> find(
                UUID userId,
                UUID tenantId) {

            findCount++;

            if (findCount == 1) {
                return Optional.empty();
            }

            return Optional.ofNullable(
                    durableAfterCollision);
        }

        int findCount() {

            return findCount;
        }

        int saveCount() {

            return saveCount;
        }
    }
}
