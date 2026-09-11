package io.github.piresrenan.orderhub.users.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.github.piresrenan.orderhub.users.application.port.in.EnsureActiveTenantMembershipUseCase;
import io.github.piresrenan.orderhub.users.application.port.in.EstablishTenantMembershipCommand;
import io.github.piresrenan.orderhub.users.application.port.in.TenantMembershipEnsureResult;
import io.github.piresrenan.orderhub.users.application.port.out.TenantMembershipRepository;
import io.github.piresrenan.orderhub.users.domain.model.TenantMembership;
import io.github.piresrenan.orderhub.users.domain.model.TenantMembershipStatus;

/**
 * Why: Historical Tenant association must not imply current operational access.
 * Covers: The membership state, concurrency or runtime composition boundary exercised by this suite.
 * Prevents: Implicit reactivation, inconsistent concurrent outcomes and missing production composition.
 */
class EnsureActiveTenantMembershipServiceTest {

    private static final UUID USER_ID =
            UUID.fromString(
                    "50000000-0000-4000-8000-000000000001");

    private static final UUID TENANT_ID =
            UUID.fromString(
                    "50000000-0000-4000-8000-000000000002");

    @Test
    void establishesAbsentMembershipAsOperational() {

        var repository =
                new InMemoryTenantMembershipRepository(
                        null);

        var result =
                service(
                        repository)
                        .ensureActive(
                                command());

        assertThat(result)
                .isInstanceOf(
                        TenantMembershipEnsureResult.Operational.class);

        assertThat(repository.saved())
                .hasSize(
                        1);

        assertThat(repository.current())
                .isPresent();

        assertThat(
                repository.current()
                        .orElseThrow()
                        .status())
                .isEqualTo(
                        TenantMembershipStatus.ACTIVE);
    }

    @Test
    void treatsAlreadyActiveMembershipAsDesiredStateSuccessWithoutRewrite() {

        var repository =
                new InMemoryTenantMembershipRepository(
                        TenantMembership.rehydrate(
                                USER_ID,
                                TENANT_ID,
                                TenantMembershipStatus.ACTIVE));

        var result =
                service(
                        repository)
                        .ensureActive(
                                command());

        assertThat(result)
                .isInstanceOf(
                        TenantMembershipEnsureResult.Operational.class);

        assertThat(repository.saved())
                .isEmpty();
    }

    @Test
    void suspendedMembershipIsNonOperationalAndIsNeverReactivatedImplicitly() {

        var suspended =
                TenantMembership.rehydrate(
                        USER_ID,
                        TENANT_ID,
                        TenantMembershipStatus.SUSPENDED);

        var repository =
                new InMemoryTenantMembershipRepository(
                        suspended);

        var result =
                service(
                        repository)
                        .ensureActive(
                                command());

        assertThat(result)
                .isInstanceOf(
                        TenantMembershipEnsureResult.NonOperational.class);

        assertThat(repository.saved())
                .isEmpty();

        assertThat(repository.current())
                .containsSame(
                        suspended);
    }

    @Test
    void terminatedMembershipIsNonOperationalAndIsNeverReactivatedImplicitly() {

        var terminated =
                TenantMembership.rehydrate(
                        USER_ID,
                        TENANT_ID,
                        TenantMembershipStatus.TERMINATED);

        var repository =
                new InMemoryTenantMembershipRepository(
                        terminated);

        var result =
                service(
                        repository)
                        .ensureActive(
                                command());

        assertThat(result)
                .isInstanceOf(
                        TenantMembershipEnsureResult.NonOperational.class);

        assertThat(repository.saved())
                .isEmpty();

        assertThat(repository.current())
                .containsSame(
                        terminated);
    }

    private EstablishTenantMembershipCommand command() {

        return new EstablishTenantMembershipCommand(
                USER_ID,
                TENANT_ID);
    }

    private EnsureActiveTenantMembershipUseCase service(
            TenantMembershipRepository repository) {

        try {

            var type =
                    Class.forName(
                            "io.github.piresrenan.orderhub.users.application.service."
                                    + "EnsureActiveTenantMembershipService");

            var constructor =
                    type.getConstructor(
                            TenantMembershipRepository.class);

            var instance =
                    constructor.newInstance(
                            repository);

            return EnsureActiveTenantMembershipUseCase.class.cast(
                    instance);

        } catch (ClassNotFoundException exception) {

            throw new AssertionError(
                    "Tenant membership ensure-active application service is missing",
                    exception);

        } catch (NoSuchMethodException exception) {

            throw new AssertionError(
                    "Tenant membership ensure-active constructor is missing",
                    exception);

        } catch (InstantiationException
                | IllegalAccessException
                | InvocationTargetException exception) {

            throw new AssertionError(
                    "Tenant membership ensure-active service could not be instantiated",
                    exception);
        }
    }

    private static final class InMemoryTenantMembershipRepository
            implements TenantMembershipRepository {

        private TenantMembership current;

        private final List<TenantMembership> saved =
                new ArrayList<>();

        private InMemoryTenantMembershipRepository(
                TenantMembership current) {

            this.current =
                    current;
        }

        @Override
        public TenantMembership save(
                TenantMembership membership) {

            saved.add(
                    membership);

            current =
                    membership;

            return membership;
        }

        @Override
        public Optional<TenantMembership> find(
                UUID userId,
                UUID tenantId) {

            if (current == null) {
                return Optional.empty();
            }

            if (!current.userId()
                    .equals(
                            userId)
                    || !current.tenantId()
                            .equals(
                                    tenantId)) {

                return Optional.empty();
            }

            return Optional.of(
                    current);
        }

        Optional<TenantMembership> current() {

            return Optional.ofNullable(
                    current);
        }

        List<TenantMembership> saved() {

            return List.copyOf(
                    saved);
        }
    }
}
