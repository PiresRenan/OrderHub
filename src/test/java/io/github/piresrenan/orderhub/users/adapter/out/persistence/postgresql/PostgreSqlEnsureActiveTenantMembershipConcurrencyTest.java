package io.github.piresrenan.orderhub.users.adapter.out.persistence.postgresql;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import io.github.piresrenan.orderhub.users.application.port.in.EstablishTenantMembershipCommand;
import io.github.piresrenan.orderhub.users.application.port.in.TenantMembershipEnsureResult;
import io.github.piresrenan.orderhub.users.application.port.out.TenantMembershipAlreadyExistsException;
import io.github.piresrenan.orderhub.users.application.port.out.TenantMembershipRepository;
import io.github.piresrenan.orderhub.users.application.service.EnsureActiveTenantMembershipService;
import io.github.piresrenan.orderhub.users.domain.model.TenantMembership;
import io.github.piresrenan.orderhub.users.domain.model.TenantMembershipStatus;

/**
 * Why: Historical Tenant association must not imply current operational access.
 * Covers: The membership state, concurrency or runtime composition boundary exercised by this suite.
 * Prevents: Implicit reactivation, inconsistent concurrent outcomes and missing production composition.
 */
@Testcontainers
class PostgreSqlEnsureActiveTenantMembershipConcurrencyTest {

    private static final int RACE_ATTEMPTS =
            32;

    private static final DockerImageName POSTGRES_IMAGE =
            DockerImageName.parse(
                    "postgres:18.6-trixie@sha256:"
                            + "4ef4dbc939d61acea57712655ddb4b4ab27419c913f94cca0cd57cb3ea3c2280")
                    .asCompatibleSubstituteFor(
                            "postgres");

    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(
                    POSTGRES_IMAGE)
                    .withDatabaseName(
                            "orderhub_test")
                    .withUsername(
                            "orderhub_test")
                    .withPassword(
                            "synthetic-test-password");

    private static JdbcTemplate jdbcTemplate;

    @BeforeAll
    static void migrateDatabase() {

        var dataSource =
                new DriverManagerDataSource(
                        POSTGRES.getJdbcUrl(),
                        POSTGRES.getUsername(),
                        POSTGRES.getPassword());

        Flyway.configure()
                .dataSource(
                        dataSource)
                .locations(
                        "classpath:db/migration")
                .load()
                .migrate();

        jdbcTemplate =
                new JdbcTemplate(
                        dataSource);
    }

    @BeforeEach
    void clearUsersSchema() {

        jdbcTemplate.update(
                """
                TRUNCATE TABLE
                    users.external_identity_link_proofs,
                    users.external_identity_bindings,
                    users.tenant_memberships,
                    users.users
                """);
    }

    @Test
    void concurrentEnsureActiveCallsConvergeThroughRealPostgresDuplicateArbitration()
            throws Exception {

        var executor =
                Executors.newFixedThreadPool(
                        2);

        try {

            for (var attempt = 0;
                    attempt < RACE_ATTEMPTS;
                    attempt++) {

                var userId =
                        UUID.randomUUID();

                var tenantId =
                        UUID.randomUUID();

                persistUser(
                        userId);

                var delegate =
                        new PostgreSqlTenantMembershipRepository(
                                jdbcTemplate);

                var bothObservedAbsent =
                        new CountDownLatch(
                                2);

                var release =
                        new CountDownLatch(
                                1);

                var duplicateCount =
                        new AtomicInteger();

                var firstRepository =
                        new BarrierAfterFirstAbsentRepository(
                                delegate,
                                bothObservedAbsent,
                                release,
                                duplicateCount);

                var secondRepository =
                        new BarrierAfterFirstAbsentRepository(
                                delegate,
                                bothObservedAbsent,
                                release,
                                duplicateCount);

                var command =
                        new EstablishTenantMembershipCommand(
                                userId,
                                tenantId);

                Future<TenantMembershipEnsureResult> first =
                        executor.submit(
                                () -> new EnsureActiveTenantMembershipService(
                                        firstRepository)
                                        .ensureActive(
                                                command));

                Future<TenantMembershipEnsureResult> second =
                        executor.submit(
                                () -> new EnsureActiveTenantMembershipService(
                                        secondRepository)
                                        .ensureActive(
                                                command));

                if (!bothObservedAbsent.await(
                        5,
                        SECONDS)) {

                    first.cancel(
                            true);

                    second.cancel(
                            true);

                    throw new AssertionError(
                            "Both ensure-active actors did not observe absence, attempt "
                                    + attempt);
                }

                release.countDown();

                var firstResult =
                        first.get(
                                10,
                                SECONDS);

                var secondResult =
                        second.get(
                                10,
                                SECONDS);

                assertThat(firstResult)
                        .as(
                                "first desired-state outcome, attempt %s",
                                attempt)
                        .isInstanceOf(
                                TenantMembershipEnsureResult.Operational.class);

                assertThat(secondResult)
                        .as(
                                "second desired-state outcome, attempt %s",
                                attempt)
                        .isInstanceOf(
                                TenantMembershipEnsureResult.Operational.class);

                assertThat(duplicateCount.get())
                        .as(
                                "PostgreSQL duplicate arbitration count, attempt %s",
                                attempt)
                        .isEqualTo(
                                1);

                assertThat(
                        countMembership(
                                userId,
                                tenantId))
                        .as(
                                "durable membership count, attempt %s",
                                attempt)
                        .isEqualTo(
                                1);

                assertThat(
                        membershipStatus(
                                userId,
                                tenantId))
                        .as(
                                "durable membership status, attempt %s",
                                attempt)
                        .isEqualTo(
                                TenantMembershipStatus.ACTIVE.name());
            }

        } finally {

            shutdown(
                    executor);
        }
    }

    @Test
    void concurrentSuspensionWinnerIsObservedWithoutImplicitReactivation()
            throws Exception {

        proveConcurrentNonOperationalWinner(
                TenantMembershipStatus.SUSPENDED);
    }

    @Test
    void concurrentTerminationWinnerIsObservedWithoutImplicitReactivation()
            throws Exception {

        proveConcurrentNonOperationalWinner(
                TenantMembershipStatus.TERMINATED);
    }

    private void proveConcurrentNonOperationalWinner(
            TenantMembershipStatus durableStatus)
            throws Exception {

        var executor =
                Executors.newFixedThreadPool(
                        2);

        try {

            for (var attempt = 0;
                    attempt < RACE_ATTEMPTS;
                    attempt++) {

                var raceAttempt =
                        attempt;

                var userId =
                        UUID.randomUUID();

                var tenantId =
                        UUID.randomUUID();

                persistUser(
                        userId);

                var delegate =
                        new PostgreSqlTenantMembershipRepository(
                                jdbcTemplate);

                var observedAbsent =
                        new CountDownLatch(
                                1);

                var durableWinnerPersisted =
                        new CountDownLatch(
                                1);

                var duplicateCount =
                        new AtomicInteger();

                var serviceRepository =
                        new PauseAfterFirstAbsentRepository(
                                delegate,
                                observedAbsent,
                                durableWinnerPersisted,
                                duplicateCount);

                var command =
                        new EstablishTenantMembershipCommand(
                                userId,
                                tenantId);

                Future<TenantMembershipEnsureResult> serviceFuture =
                        executor.submit(
                                () -> new EnsureActiveTenantMembershipService(
                                        serviceRepository)
                                        .ensureActive(
                                                command));

                Future<Void> competingWriter =
                        executor.submit(
                                () -> {

                                    if (!observedAbsent.await(
                                            5,
                                            SECONDS)) {

                                        throw new AssertionError(
                                                "Ensure-active actor did not observe absence, attempt "
                                                        + raceAttempt);
                                    }

                                    try {

                                        delegate.save(
                                                TenantMembership.rehydrate(
                                                        userId,
                                                        tenantId,
                                                        durableStatus));

                                        return null;

                                    } finally {

                                        durableWinnerPersisted.countDown();
                                    }
                                });

                competingWriter.get(
                        10,
                        SECONDS);

                var result =
                        serviceFuture.get(
                                10,
                                SECONDS);

                assertThat(result)
                        .as(
                                "%s desired-state outcome, attempt %s",
                                durableStatus,
                                attempt)
                        .isInstanceOf(
                                TenantMembershipEnsureResult.NonOperational.class);

                assertThat(duplicateCount.get())
                        .as(
                                "%s duplicate recovery count, attempt %s",
                                durableStatus,
                                attempt)
                        .isEqualTo(
                                1);

                assertThat(
                        countMembership(
                                userId,
                                tenantId))
                        .as(
                                "%s durable membership count, attempt %s",
                                durableStatus,
                                attempt)
                        .isEqualTo(
                                1);

                assertThat(
                        membershipStatus(
                                userId,
                                tenantId))
                        .as(
                                "%s durable membership status, attempt %s",
                                durableStatus,
                                attempt)
                        .isEqualTo(
                                durableStatus.name());
            }

        } finally {

            shutdown(
                    executor);
        }
    }

    private void persistUser(
            UUID userId) {

        jdbcTemplate.update(
                """
                INSERT INTO users.users (
                    id
                )
                VALUES (?)
                """,
                userId);
    }

    private int countMembership(
            UUID userId,
            UUID tenantId) {

        var count =
                jdbcTemplate.queryForObject(
                        """
                        SELECT count(*)
                        FROM users.tenant_memberships
                        WHERE user_id = ?
                          AND tenant_id = ?
                        """,
                        Integer.class,
                        userId,
                        tenantId);

        if (count == null) {
            throw new AssertionError(
                    "PostgreSQL did not return membership count");
        }

        return count;
    }

    private String membershipStatus(
            UUID userId,
            UUID tenantId) {

        return jdbcTemplate.queryForObject(
                """
                SELECT status
                FROM users.tenant_memberships
                WHERE user_id = ?
                  AND tenant_id = ?
                """,
                String.class,
                userId,
                tenantId);
    }

    private void shutdown(
            ExecutorService executor)
            throws InterruptedException {

        executor.shutdownNow();

        if (!executor.awaitTermination(
                5,
                SECONDS)) {

            throw new AssertionError(
                    "Membership race executor did not terminate");
        }
    }

    /**
     * Delegates every persistence operation to the real PostgreSQL adapter but
     * pauses each actor after its first durable absence observation. Releasing
     * both actors together guarantees that neither can win by observing the
     * other actor's already-committed membership.
     */
    private static final class BarrierAfterFirstAbsentRepository
            implements TenantMembershipRepository {

        private final TenantMembershipRepository delegate;

        private final CountDownLatch bothObservedAbsent;

        private final CountDownLatch release;

        private final AtomicInteger duplicateCount;

        private final AtomicBoolean firstFind =
                new AtomicBoolean(
                        true);

        private BarrierAfterFirstAbsentRepository(
                TenantMembershipRepository delegate,
                CountDownLatch bothObservedAbsent,
                CountDownLatch release,
                AtomicInteger duplicateCount) {

            this.delegate =
                    delegate;

            this.bothObservedAbsent =
                    bothObservedAbsent;

            this.release =
                    release;

            this.duplicateCount =
                    duplicateCount;
        }

        @Override
        public TenantMembership save(
                TenantMembership membership) {

            try {

                return delegate.save(
                        membership);

            } catch (TenantMembershipAlreadyExistsException duplicate) {

                duplicateCount.incrementAndGet();

                throw duplicate;
            }
        }

        @Override
        public Optional<TenantMembership> find(
                UUID userId,
                UUID tenantId) {

            var result =
                    delegate.find(
                            userId,
                            tenantId);

            if (firstFind.compareAndSet(
                    true,
                    false)) {

                if (result.isPresent()) {
                    throw new AssertionError(
                            "Coordinated initial membership read was not absent");
                }

                bothObservedAbsent.countDown();

                try {

                    if (!release.await(
                            5,
                            SECONDS)) {

                        throw new AssertionError(
                                "Concurrent ensure-active actors were not released");
                    }

                } catch (InterruptedException exception) {

                    Thread.currentThread()
                            .interrupt();

                    throw new AssertionError(
                            "Concurrent ensure-active actor was interrupted",
                            exception);
                }
            }

            return result;
        }
    }

    /**
     * Delegates every persistence operation to the real PostgreSQL adapter.
     * Only the first absent read is paused long enough for the competing
     * durable non-operational state to commit before this actor attempts its
     * ACTIVE insert.
     */
    private static final class PauseAfterFirstAbsentRepository
            implements TenantMembershipRepository {

        private final TenantMembershipRepository delegate;

        private final CountDownLatch observedAbsent;

        private final CountDownLatch durableWinnerPersisted;

        private final AtomicInteger duplicateCount;

        private final AtomicBoolean firstFind =
                new AtomicBoolean(
                        true);

        private PauseAfterFirstAbsentRepository(
                TenantMembershipRepository delegate,
                CountDownLatch observedAbsent,
                CountDownLatch durableWinnerPersisted,
                AtomicInteger duplicateCount) {

            this.delegate =
                    delegate;

            this.observedAbsent =
                    observedAbsent;

            this.durableWinnerPersisted =
                    durableWinnerPersisted;

            this.duplicateCount =
                    duplicateCount;
        }

        @Override
        public TenantMembership save(
                TenantMembership membership) {

            try {

                return delegate.save(
                        membership);

            } catch (TenantMembershipAlreadyExistsException duplicate) {

                duplicateCount.incrementAndGet();

                throw duplicate;
            }
        }

        @Override
        public Optional<TenantMembership> find(
                UUID userId,
                UUID tenantId) {

            var result =
                    delegate.find(
                            userId,
                            tenantId);

            if (firstFind.compareAndSet(
                    true,
                    false)) {

                if (result.isPresent()) {
                    throw new AssertionError(
                            "Coordinated initial membership read was not absent");
                }

                observedAbsent.countDown();

                try {

                    if (!durableWinnerPersisted.await(
                            5,
                            SECONDS)) {

                        throw new AssertionError(
                                "Concurrent non-operational winner was not persisted");
                    }

                } catch (InterruptedException exception) {

                    Thread.currentThread()
                            .interrupt();

                    throw new AssertionError(
                            "Ensure-active actor was interrupted",
                            exception);
                }
            }

            return result;
        }
    }
}
