package io.github.piresrenan.orderhub.users.adapter.out.persistence.postgresql;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import io.github.piresrenan.orderhub.users.application.port.in.BindExternalIdentityCommand;
import io.github.piresrenan.orderhub.users.application.port.in.BindExternalIdentityUseCase;
import io.github.piresrenan.orderhub.users.application.port.in.CreateUserUseCase;
import io.github.piresrenan.orderhub.users.application.port.in.CreatedUserIdentity;
import io.github.piresrenan.orderhub.users.application.port.in.ResolveExternalIdentityQuery;
import io.github.piresrenan.orderhub.users.application.port.in.ResolveExternalIdentityUseCase;
import io.github.piresrenan.orderhub.users.application.port.in.ResolveOrCreateExternalUserUseCase;
import io.github.piresrenan.orderhub.users.application.port.in.ResolvedUserIdentity;
import io.github.piresrenan.orderhub.users.application.service.BindExternalIdentityService;
import io.github.piresrenan.orderhub.users.application.service.CreateUserService;
import io.github.piresrenan.orderhub.users.application.service.ResolveExternalIdentityService;
import io.github.piresrenan.orderhub.users.application.service.ResolveOrCreateExternalUserService;

/**
 * End-to-end concurrency proof for external User provisioning through the real
 * Users production graph.
 *
 * <p>Both racing actors are complete independent graphs: the real
 * ResolveOrCreateExternalUserService composed over the real PostgreSQL
 * serialization coordinator, the real resolve, create and bind application
 * services, and the real PostgreSQL repositories. Nothing about resolving,
 * creating or binding is reimplemented here.</p>
 *
 * <p>The only test-owned types are synchronization decorators that delegate to
 * those production use cases. They make the race deterministic by holding the
 * first actor open inside its own coordination scope while the second actor is
 * launched, and by observing when each actor reaches a production step.</p>
 *
 * <p>The invariant under proof is the one that matters to authentication: two
 * concurrent first-sightings of the same verified external identity must
 * converge on exactly one durable internal User and exactly one durable
 * binding, with both callers succeeding and observing the same User.</p>
 */
@Testcontainers
class PostgreSqlResolveOrCreateExternalUserConcurrencyTest {

    private static final int RACE_ATTEMPTS =
            32;

    private static final int SCOPE_TIMEOUT_SECONDS =
            15;

    private static final int LOCK_WAIT_OBSERVATION_SECONDS =
            10;

    private static final long LOCK_WAIT_POLL_MILLIS =
            10;

    private static final String ISSUER_PREFIX =
            "https://identity.example.test/tenant/";

    private static final String SUBJECT_PREFIX =
            "external-subject-";

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

    private static PlatformTransactionManager transactionManager;

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

        transactionManager =
                new JdbcTransactionManager(
                        dataSource);
    }

    @BeforeEach
    void clearUsersSchema() {

        truncateUsersSchema();
    }

    @Test
    void concurrentFirstSightingsOfOneExternalIdentityProvisionExactlyOneUser()
            throws Exception {

        // Why: two OrderHub instances can authenticate the same verified external
        // identity for the very first time at the same moment, and neither caller
        // may be told to retry or receive a different internal User.
        // Covers: the real production graph end to end, with the first actor held
        // open inside its own coordination scope while the second actor races it,
        // repeated over independent external identities.
        // Prevents: a losing speculative User, a second binding, a duplicate-key
        // failure surfacing as a caller outcome, and any serialization that only
        // appears to work because the two calls happened to run sequentially.

        var executor =
                Executors.newFixedThreadPool(
                        2);

        try {

            for (var attempt = 0;
                    attempt < RACE_ATTEMPTS;
                    attempt++) {

                raceOneExternalIdentity(
                        executor,
                        attempt);
            }

        } finally {

            shutdown(
                    executor);
        }
    }

    private void raceOneExternalIdentity(
            ExecutorService executor,
            int attempt)
            throws Exception {

        truncateUsersSchema();

        var issuer =
                ISSUER_PREFIX
                        + attempt;

        var subject =
                SUBJECT_PREFIX
                        + attempt;

        var query =
                new ResolveExternalIdentityQuery(
                        issuer,
                        subject);

        var creations =
                new AtomicInteger();

        var bindings =
                new AtomicInteger();

        var readyToCreate =
                new CountDownLatch(
                        1);

        var releaseFirst =
                new CountDownLatch(
                        1);

        var secondStarted =
                new CountDownLatch(
                        1);

        var secondEnteredScope =
                new CountDownLatch(
                        1);

        var firstProvisioning =
                heldProvisioningGraph(
                        readyToCreate,
                        releaseFirst,
                        creations,
                        bindings);

        var secondProvisioning =
                competingProvisioningGraph(
                        secondEnteredScope,
                        creations,
                        bindings);

        try {

            Future<ResolvedUserIdentity> first =
                    executor.submit(
                            () -> firstProvisioning.resolveOrCreate(
                                    query));

            requireLatch(
                    readyToCreate,
                    "The first provisioning actor never reached User creation, attempt "
                            + attempt);

            Future<ResolvedUserIdentity> second =
                    executor.submit(
                            () -> {

                                secondStarted.countDown();

                                return secondProvisioning.resolveOrCreate(
                                        query);
                            });

            requireLatch(
                    secondStarted,
                    "The second provisioning actor never started, attempt "
                            + attempt);

            assertThat(
                    awaitBlockedAdvisoryLock())
                    .as("PostgreSQL must report the competing actor waiting on an"
                            + " advisory lock while the first actor still holds its"
                            + " provisioning scope open, attempt %s",
                            attempt)
                    .isTrue();

            assertThat(
                    secondEnteredScope.getCount())
                    .as("the competing actor must not have entered its critical region"
                            + " while the first actor is still open, attempt %s",
                            attempt)
                    .isEqualTo(
                            1);

            assertThat(
                    second.isDone())
                    .as("the competing actor must not have completed before the first"
                            + " actor was released, attempt %s",
                            attempt)
                    .isFalse();

            releaseFirst.countDown();

            var firstResult =
                    resultWithin(
                            first,
                            "The first provisioning actor did not complete, attempt "
                                    + attempt);

            var secondResult =
                    resultWithin(
                            second,
                            "The competing provisioning actor did not complete after the"
                                    + " first actor committed, attempt "
                                    + attempt);

            assertDurableProvisioning(
                    attempt,
                    issuer,
                    subject,
                    firstResult,
                    secondResult,
                    creations,
                    bindings);

        } finally {

            releaseFirst.countDown();
        }
    }

    private void assertDurableProvisioning(
            int attempt,
            String issuer,
            String subject,
            ResolvedUserIdentity firstResult,
            ResolvedUserIdentity secondResult,
            AtomicInteger creations,
            AtomicInteger bindings) {

        assertThat(
                secondResult.userId())
                .as("both concurrent callers must observe the same internal User,"
                        + " attempt %s",
                        attempt)
                .isEqualTo(
                        firstResult.userId());

        assertThat(
                creations.get())
                .as("exactly one real User creation may happen per raced external"
                        + " identity, attempt %s",
                        attempt)
                .isEqualTo(
                        1);

        assertThat(
                bindings.get())
                .as("exactly one real binding may be written per raced external"
                        + " identity, attempt %s",
                        attempt)
                .isEqualTo(
                        1);

        assertThat(
                countUsers())
                .as("no losing speculative User may remain durable, attempt %s",
                        attempt)
                .isEqualTo(
                        1);

        assertThat(
                countBindings(
                        issuer,
                        subject))
                .as("exactly one durable binding may exist for the raced external"
                        + " identity, attempt %s",
                        attempt)
                .isEqualTo(
                        1);

        assertThat(
                durableBindingUserId(
                        issuer,
                        subject))
                .as("the durable binding must reference the User both callers"
                        + " observed, attempt %s",
                        attempt)
                .isEqualTo(
                        firstResult.userId());
    }

    /**
     * Builds one complete production provisioning graph whose User creation step
     * is held open, so the first actor keeps its coordination scope and its
     * transaction while the competing actor races it.
     *
     * @param readyToCreate signalled once the first actor reaches User creation
     * @param releaseFirst  awaited before the real creation is delegated
     * @param creations     counts real User creations across both graphs
     * @param bindings      counts real binding writes across both graphs
     * @return production resolve-or-create use case with a held creation step
     */
    private ResolveOrCreateExternalUserUseCase heldProvisioningGraph(
            CountDownLatch readyToCreate,
            CountDownLatch releaseFirst,
            AtomicInteger creations,
            AtomicInteger bindings) {

        var bindingRepository =
                new PostgreSqlExternalIdentityBindingRepository(
                        jdbcTemplate);

        var userRepository =
                new PostgreSqlUserRepository(
                        jdbcTemplate);

        return new ResolveOrCreateExternalUserService(
                new PostgreSqlExternalIdentitySerializationCoordinator(
                        jdbcTemplate,
                        transactionManager),
                new ResolveExternalIdentityService(
                        bindingRepository),
                new HeldCreateUser(
                        new CreateUserService(
                                userRepository,
                                UUID::randomUUID),
                        readyToCreate,
                        releaseFirst,
                        creations),
                new CountingBindExternalIdentity(
                        new BindExternalIdentityService(
                                bindingRepository),
                        bindings));
    }

    /**
     * Builds one complete production provisioning graph for the competing actor,
     * observing only when it reaches the resolution step inside its coordination
     * scope.
     *
     * @param enteredScope signalled once the competing actor resolves inside its
     *                     coordination scope
     * @param creations    counts real User creations across both graphs
     * @param bindings     counts real binding writes across both graphs
     * @return production resolve-or-create use case with observed entry
     */
    private ResolveOrCreateExternalUserUseCase competingProvisioningGraph(
            CountDownLatch enteredScope,
            AtomicInteger creations,
            AtomicInteger bindings) {

        var bindingRepository =
                new PostgreSqlExternalIdentityBindingRepository(
                        jdbcTemplate);

        var userRepository =
                new PostgreSqlUserRepository(
                        jdbcTemplate);

        return new ResolveOrCreateExternalUserService(
                new PostgreSqlExternalIdentitySerializationCoordinator(
                        jdbcTemplate,
                        transactionManager),
                new ScopeEntryAwareResolve(
                        new ResolveExternalIdentityService(
                                bindingRepository),
                        enteredScope),
                new CountingCreateUser(
                        new CreateUserService(
                                userRepository,
                                UUID::randomUUID),
                        creations),
                new CountingBindExternalIdentity(
                        new BindExternalIdentityService(
                                bindingRepository),
                        bindings));
    }

    private void truncateUsersSchema() {

        jdbcTemplate.update(
                """
                TRUNCATE TABLE
                    users.external_identity_bindings,
                    users.tenant_memberships,
                    users.users
                """);
    }

    private int countUsers() {

        var count =
                jdbcTemplate.queryForObject(
                        """
                        SELECT count(*)
                        FROM users.users
                        """,
                        Integer.class);

        if (count == null) {
            throw new AssertionError(
                    "PostgreSQL did not return a User count");
        }

        return count;
    }

    private int countBindings(
            String issuer,
            String subject) {

        var count =
                jdbcTemplate.queryForObject(
                        """
                        SELECT count(*)
                        FROM users.external_identity_bindings
                        WHERE issuer = ?
                          AND subject = ?
                        """,
                        Integer.class,
                        issuer,
                        subject);

        if (count == null) {
            throw new AssertionError(
                    "PostgreSQL did not return a binding count");
        }

        return count;
    }

    private UUID durableBindingUserId(
            String issuer,
            String subject) {

        return jdbcTemplate.query(
                        """
                        SELECT user_id
                        FROM users.external_identity_bindings
                        WHERE issuer = ?
                          AND subject = ?
                        """,
                        (resultSet, rowNumber) ->
                                resultSet.getObject(
                                        "user_id",
                                        UUID.class),
                        issuer,
                        subject)
                .stream()
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "No durable binding exists for the raced external identity"));
    }

    /**
     * Observes the lock view of PostgreSQL while the competing actor is expected
     * to be waiting. Actor coordination is latch-based; this bounded poll only
     * samples durable engine state that offers no notification channel.
     *
     * @return true once PostgreSQL reports an ungranted advisory lock
     * @throws InterruptedException when the observing thread is interrupted
     */
    private boolean awaitBlockedAdvisoryLock()
            throws InterruptedException {

        var deadline =
                System.nanoTime()
                        + SECONDS.toNanos(
                                LOCK_WAIT_OBSERVATION_SECONDS);

        while (System.nanoTime() < deadline) {

            var waiting =
                    jdbcTemplate.queryForObject(
                            """
                            SELECT EXISTS (
                                SELECT 1
                                FROM pg_locks
                                WHERE locktype = 'advisory'
                                  AND granted = FALSE
                            )
                            """,
                            Boolean.class);

            if (Boolean.TRUE.equals(
                    waiting)) {

                return true;
            }

            Thread.sleep(
                    LOCK_WAIT_POLL_MILLIS);
        }

        return false;
    }

    private void requireLatch(
            CountDownLatch latch,
            String description)
            throws InterruptedException {

        if (!latch.await(
                SCOPE_TIMEOUT_SECONDS,
                SECONDS)) {

            throw new AssertionError(
                    description);
        }
    }

    private <T> T resultWithin(
            Future<T> future,
            String description)
            throws Exception {

        try {

            return future.get(
                    SCOPE_TIMEOUT_SECONDS,
                    SECONDS);

        } catch (TimeoutException exception) {

            future.cancel(
                    true);

            throw new AssertionError(
                    description,
                    exception);
        }
    }

    private void shutdown(
            ExecutorService executor)
            throws InterruptedException {

        executor.shutdownNow();

        if (!executor.awaitTermination(
                SCOPE_TIMEOUT_SECONDS,
                SECONDS)) {

            throw new AssertionError(
                    "External User provisioning race executor did not terminate");
        }
    }

    private static void awaitRelease(
            CountDownLatch release,
            String description) {

        try {

            if (!release.await(
                    SCOPE_TIMEOUT_SECONDS,
                    SECONDS)) {

                throw new AssertionError(
                        description);
            }

        } catch (InterruptedException exception) {

            Thread.currentThread()
                    .interrupt();

            throw new AssertionError(
                    description,
                    exception);
        }
    }

    /**
     * Delegates to the real User creation use case, but only after announcing
     * that the holding actor reached creation and after the race has released
     * it. The User itself is always created by production code.
     */
    private static final class HeldCreateUser
            implements CreateUserUseCase {

        private final CreateUserUseCase delegate;

        private final CountDownLatch readyToCreate;

        private final CountDownLatch releaseFirst;

        private final AtomicInteger creations;

        private HeldCreateUser(
                CreateUserUseCase delegate,
                CountDownLatch readyToCreate,
                CountDownLatch releaseFirst,
                AtomicInteger creations) {

            this.delegate =
                    delegate;

            this.readyToCreate =
                    readyToCreate;

            this.releaseFirst =
                    releaseFirst;

            this.creations =
                    creations;
        }

        @Override
        public CreatedUserIdentity create() {

            readyToCreate.countDown();

            awaitRelease(
                    releaseFirst,
                    "The held provisioning actor was never released");

            var created =
                    delegate.create();

            creations.incrementAndGet();

            return created;
        }
    }

    /**
     * Delegates to the real User creation use case and counts every creation, so
     * a second speculative User is visible even before durable state is
     * inspected.
     */
    private static final class CountingCreateUser
            implements CreateUserUseCase {

        private final CreateUserUseCase delegate;

        private final AtomicInteger creations;

        private CountingCreateUser(
                CreateUserUseCase delegate,
                AtomicInteger creations) {

            this.delegate =
                    delegate;

            this.creations =
                    creations;
        }

        @Override
        public CreatedUserIdentity create() {

            var created =
                    delegate.create();

            creations.incrementAndGet();

            return created;
        }
    }

    /**
     * Delegates to the real external identity binding use case and counts every
     * durable binding write.
     */
    private static final class CountingBindExternalIdentity
            implements BindExternalIdentityUseCase {

        private final BindExternalIdentityUseCase delegate;

        private final AtomicInteger bindings;

        private CountingBindExternalIdentity(
                BindExternalIdentityUseCase delegate,
                AtomicInteger bindings) {

            this.delegate =
                    delegate;

            this.bindings =
                    bindings;
        }

        @Override
        public void bind(
                BindExternalIdentityCommand command) {

            delegate.bind(
                    command);

            bindings.incrementAndGet();
        }
    }

    /**
     * Delegates to the real external identity resolution use case and announces
     * the moment the actor reaches it. Resolution only happens inside the
     * provisioning coordination scope, so this signal marks scope entry.
     */
    private static final class ScopeEntryAwareResolve
            implements ResolveExternalIdentityUseCase {

        private final ResolveExternalIdentityUseCase delegate;

        private final CountDownLatch enteredScope;

        private ScopeEntryAwareResolve(
                ResolveExternalIdentityUseCase delegate,
                CountDownLatch enteredScope) {

            this.delegate =
                    delegate;

            this.enteredScope =
                    enteredScope;
        }

        @Override
        public Optional<ResolvedUserIdentity> resolve(
                ResolveExternalIdentityQuery query) {

            enteredScope.countDown();

            return delegate.resolve(
                    query);
        }
    }
}
