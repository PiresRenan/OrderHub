package io.github.piresrenan.orderhub.users.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.github.piresrenan.orderhub.users.application.port.in.EstablishTenantMembershipCommand;
import io.github.piresrenan.orderhub.users.application.port.out.TenantMembershipAlreadyExistsException;
import io.github.piresrenan.orderhub.users.application.port.out.TenantMembershipRepository;
import io.github.piresrenan.orderhub.users.domain.model.TenantMembership;

class EstablishTenantMembershipServiceTest {

        @Test
        void establishesAndPersistsMembership() {
                // Why: membership establishment must pass through the domain model before
                // durable persistence.
                // Covers: valid User/Tenant association orchestration.
                // Prevents: application code persisting ad-hoc identity pairs without domain
                // invariant enforcement.

                var userId = UUID.randomUUID();
                var tenantId = UUID.randomUUID();
                var repository = new RecordingTenantMembershipRepository();

                var service = new EstablishTenantMembershipService(
                                repository);

                var membership = service.establish(
                                new EstablishTenantMembershipCommand(
                                                userId,
                                                tenantId));

                assertThat(membership.userId())
                                .isEqualTo(userId);

                assertThat(membership.tenantId())
                                .isEqualTo(tenantId);

                assertThat(repository.savedMembership)
                                .isSameAs(membership);

                assertThat(repository.saveCount)
                                .isEqualTo(1);
        }

        @Test
        void doesNotPersistInvalidMembership() {
                // Why: repository boundaries must receive only memberships satisfying domain
                // identity requirements.
                // Covers: ordering between domain construction and persistence.
                // Prevents: incomplete association state crossing into persistence.

                var repository = new RecordingTenantMembershipRepository();

                var service = new EstablishTenantMembershipService(
                                repository);

                var command = new EstablishTenantMembershipCommand(
                                UUID.randomUUID(),
                                null);

                assertThatThrownBy(() -> service.establish(command))
                                .isInstanceOf(IllegalArgumentException.class)
                                .hasMessage("Membership tenant id is required");

                assertThat(repository.saveCount)
                                .isZero();

                assertThat(repository.savedMembership)
                                .isNull();
        }

        @Test
        void propagatesDuplicateMembershipConflict() {
                // Why: duplicate membership is a deterministic application-level conflict,
                // not a generic infrastructure failure.
                // Covers: propagation of the repository duplicate-membership contract through
                // the establishment use case.
                // Prevents: the service swallowing or incorrectly translating an already
                // classified membership conflict.

                var service = new EstablishTenantMembershipService(
                                new DuplicateRejectingTenantMembershipRepository());

                var command = new EstablishTenantMembershipCommand(
                                UUID.randomUUID(),
                                UUID.randomUUID());

                assertThatThrownBy(() -> service.establish(command))
                                .isInstanceOf(TenantMembershipAlreadyExistsException.class)
                                .hasMessage("Tenant membership already exists.");
        }

        private static final class RecordingTenantMembershipRepository
                        implements TenantMembershipRepository {

                private TenantMembership savedMembership;
                private int saveCount;

                /**
                 * Records the membership supplied through the persistence output boundary.
                 *
                 * @param membership valid association requested for persistence
                 * @return the same membership supplied by the application service
                 */
                @Override
                public TenantMembership save(TenantMembership membership) {
                        this.savedMembership = membership;
                        this.saveCount++;

                        return membership;
                }

                /**
                 * Satisfies pair lookup without introducing behavior unrelated to
                 * establishment orchestration.
                 *
                 * @param userId   internal User identifier
                 * @param tenantId Tenant identifier
                 * @return always empty for this focused test double
                 */
                @Override
                public Optional<TenantMembership> find(
                                UUID userId,
                                UUID tenantId) {

                        return Optional.empty();
                }
        }

        private static final class DuplicateRejectingTenantMembershipRepository
                        implements TenantMembershipRepository {

                /**
                 * Simulates a persistence boundary that has already classified the requested
                 * User/Tenant pair as an existing membership.
                 *
                 * @param membership membership whose persistence is being attempted
                 * @return never returns because the membership is rejected as duplicate
                 * @throws TenantMembershipAlreadyExistsException always
                 */
                @Override
                public TenantMembership save(
                                TenantMembership membership) {

                        throw new TenantMembershipAlreadyExistsException();
                }

                /**
                 * Satisfies the repository lookup contract without introducing behavior
                 * unrelated to duplicate-conflict propagation.
                 *
                 * @param userId   internal User identifier
                 * @param tenantId Tenant identifier
                 * @return always empty for this focused test double
                 */
                @Override
                public Optional<TenantMembership> find(
                                UUID userId,
                                UUID tenantId) {

                        return Optional.empty();
                }
        }

}
