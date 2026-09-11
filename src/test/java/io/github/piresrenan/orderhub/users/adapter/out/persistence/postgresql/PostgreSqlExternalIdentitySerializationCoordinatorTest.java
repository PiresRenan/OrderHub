package io.github.piresrenan.orderhub.users.adapter.out.persistence.postgresql;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import io.github.piresrenan.orderhub.users.application.port.out.ExternalIdentityUserProvisioningCoordinator;

/**
 * Executable contract for the PostgreSQL implementation of the Users-owned
 * external identity provisioning coordination scope.
 *
 * <p>The scope must serialize concurrent work for one exact issuer/subject pair
 * inside PostgreSQL itself, so competing OrderHub instances observe the same
 * arbitration instead of a mutex that exists only inside one JVM. Unrelated
 * external identities must remain independent, so serialization cannot be one
 * global application-wide lock.</p>
 *
 * <p>The scope must also be one real transaction: the lock is acquired inside
 * it, the coordinated work executes while the lock is still held, and failing
 * work rolls back together with the lock. When an ambient transaction already
 * exists the scope participates in it rather than committing independently.</p>
 *
 * <p>These tests deliberately constrain observable behaviour only. The
 * derivation used to turn issuer and subject into an advisory lock key stays an
 * implementation decision and is intentionally not frozen here.</p>
 */
@Testcontainers
class PostgreSqlExternalIdentitySerializationCoordinatorTest {

    private static final String ISSUER =
            "https://identity.example.test/tenant";

    private static final String SUBJECT =
            "external-subject-001";

    private static final String OTHER_ISSUER =
            "https://other-identity.example.test/tenant";

    private static final String OTHER_SUBJECT =
            "external-subject-002";

    private static final int SCOPE_TIMEOUT_SECONDS =
            15;

    private static final int BLOCKED_OBSERVATION_SECONDS =
            2;

    private static final int LOCK_WAIT_OBSERVATION_SECONDS =
            10;

    private static final long LOCK_WAIT_POLL_MILLIS =
            10;

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

    private static TransactionTemplate transactionTemplate;

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

        transactionTemplate =
                new TransactionTemplate(
                        transactionManager);
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
    void concurrentScopesForTheSameExternalIdentityAreSerializedByPostgreSql()
            throws Exception {

        // Why: two OrderHub instances resolving the same verified external
        // identity must not both observe an absent binding and both create a
        // User for it.
        // Covers: mutual exclusion of same-pair scopes, proven while the first
        // critical region is still open, plus PostgreSQL itself reporting the
        // waiting backend.
        // Prevents: a JVM-local mutex that cannot serialize across replicas,
        // and an advisory lock acquired in autocommit and therefore already
        // released before the coordinated work runs.

        var firstCoordinator =
                coordinator();

        var secondCoordinator =
                coordinator();

        var firstEntered =
                new CountDownLatch(
                        1);

        var releaseFirst =
                new CountDownLatch(
                        1);

        var secondStarted =
                new CountDownLatch(
                        1);

        var secondEntered =
                new CountDownLatch(
                        1);

        var events =
                new ConcurrentLinkedQueue<String>();

        var executor =
                Executors.newFixedThreadPool(
                        2);

        try {

            Future<String> first =
                    executor.submit(
                            () -> firstCoordinator.executeSerialized(
                                    ISSUER,
                                    SUBJECT,
                                    () -> {

                                        events.add(
                                                "first-entered");

                                        firstEntered.countDown();

                                        awaitRelease(
                                                releaseFirst,
                                                "The first serialized scope was never released");

                                        events.add(
                                                "first-leaving");

                                        return "first";
                                    }));

            requireLatch(
                    firstEntered,
                    "The first serialized scope never entered its critical region");

            Future<String> second =
                    executor.submit(
                            () -> {

                                secondStarted.countDown();

                                return secondCoordinator.executeSerialized(
                                        ISSUER,
                                        SUBJECT,
                                        () -> {

                                            events.add(
                                                    "second-entered");

                                            secondEntered.countDown();

                                            return "second";
                                        });
                            });

            requireLatch(
                    secondStarted,
                    "The second serialized scope task never started");

            assertThat(
                    secondEntered.await(
                            BLOCKED_OBSERVATION_SECONDS,
                            SECONDS))
                    .as("a second scope for the same external identity must not enter its"
                            + " critical region while the first scope is still open")
                    .isFalse();

            assertThat(
                    awaitBlockedAdvisoryLock())
                    .as("PostgreSQL must report a backend waiting on an advisory lock,"
                            + " proving same-identity scopes are serialized by the database"
                            + " itself and not by process-local memory")
                    .isTrue();

            releaseFirst.countDown();

            assertThat(
                    resultWithin(
                            first,
                            "The first serialized scope did not complete"))
                    .isEqualTo(
                            "first");

            assertThat(
                    resultWithin(
                            second,
                            "The second serialized scope did not complete after the first"
                                    + " scope ended its transaction"))
                    .isEqualTo(
                            "second");

            assertThat(
                    List.copyOf(
                            events))
                    .as("the second scope must only enter after the first scope left its"
                            + " critical region")
                    .containsExactly(
                            "first-entered",
                            "first-leaving",
                            "second-entered");

        } finally {

            releaseFirst.countDown();

            shutdown(
                    executor);
        }
    }

    @Test
    void anOpenScopeDoesNotBlockADifferentExternalIdentity()
            throws Exception {

        // Why: provisioning one external identity must never stall provisioning
        // of every other identity in the platform.
        // Covers: an unrelated issuer/subject pair entering and completing its
        // scope while a different pair still holds an open scope.
        // Prevents: one global lock, or one coarse table-level lock, being
        // mistaken for identity-scoped serialization.

        var holdingCoordinator =
                coordinator();

        var otherCoordinator =
                coordinator();

        var holderEntered =
                new CountDownLatch(
                        1);

        var releaseHolder =
                new CountDownLatch(
                        1);

        var otherEntered =
                new CountDownLatch(
                        1);

        var executor =
                Executors.newFixedThreadPool(
                        2);

        try {

            Future<String> holder =
                    executor.submit(
                            () -> holdingCoordinator.executeSerialized(
                                    ISSUER,
                                    SUBJECT,
                                    () -> {

                                        holderEntered.countDown();

                                        awaitRelease(
                                                releaseHolder,
                                                "The holding serialized scope was never released");

                                        return "holder";
                                    }));

            requireLatch(
                    holderEntered,
                    "The holding serialized scope never entered its critical region");

            Future<String> other =
                    executor.submit(
                            () -> otherCoordinator.executeSerialized(
                                    OTHER_ISSUER,
                                    OTHER_SUBJECT,
                                    () -> {

                                        otherEntered.countDown();

                                        return "other";
                                    }));

            assertThat(
                    otherEntered.await(
                            SCOPE_TIMEOUT_SECONDS,
                            SECONDS))
                    .as("a scope for a different external identity must not wait for an"
                            + " unrelated open scope")
                    .isTrue();

            assertThat(
                    resultWithin(
                            other,
                            "The unrelated serialized scope did not complete"))
                    .isEqualTo(
                            "other");

            assertThat(
                    holder.isDone())
                    .as("the unrelated scope must have completed while the first external"
                            + " identity still held its own open scope")
                    .isFalse();

            releaseHolder.countDown();

            assertThat(
                    resultWithin(
                            holder,
                            "The holding serialized scope did not complete"))
                    .isEqualTo(
                            "holder");

        } finally {

            releaseHolder.countDown();

            shutdown(
                    executor);
        }
    }

    @Test
    void theSerializedScopeReturnsTheExactCallbackResult() {

        // Why: the coordination scope is infrastructure and must stay
        // semantically transparent to the Users application work it wraps.
        // Covers: identity of the value produced by the coordinated work.
        // Prevents: a future implementation substituting, copying or
        // re-deriving the application result.

        var expectedResult =
                new Object();

        var result =
                coordinator().executeSerialized(
                        ISSUER,
                        SUBJECT,
                        () -> expectedResult);

        assertThat(result)
                .as("the coordination scope must return the exact coordinated result")
                .isSameAs(
                        expectedResult);
    }

    @Test
    void coordinatedWorkFailurePropagatesWithoutWrapping() {

        // Why: Users application services classify their own failures, so the
        // coordination scope must not reinterpret them.
        // Covers: identity of a RuntimeException raised by the coordinated
        // work.
        // Prevents: a technical wrapper hiding the application failure from the
        // caller that knows how to classify it.

        var failure =
                new IllegalStateException(
                        "synthetic serialized scope failure");

        assertThatThrownBy(
                () -> coordinator().executeSerialized(
                        ISSUER,
                        SUBJECT,
                        () -> {
                            throw failure;
                        }))
                .as("the coordination scope must not wrap or replace the failure raised by"
                        + " the coordinated work")
                .isSameAs(
                        failure);
    }

    @Test
    void coordinatedWorkFailureRollsBackDatabaseWorkPerformedInsideTheScope() {

        // Why: the resolve/create/bind work runs inside this scope, so a failure
        // must not leave a User committed without its binding.
        // Covers: a real Users write performed inside the scope, observed inside
        // the scope's own transaction and absent after the failure.
        // Prevents: the advisory lock being taken in autocommit around work that
        // commits independently of the coordination scope.

        var userId =
                UUID.randomUUID();

        var failure =
                new IllegalStateException(
                        "synthetic serialized scope write failure");

        assertThatThrownBy(
                () -> coordinator().executeSerialized(
                        ISSUER,
                        SUBJECT,
                        () -> {

                            assertThat(
                                    TransactionSynchronizationManager
                                            .isActualTransactionActive())
                                    .as("the coordinated work must execute inside one real"
                                            + " transaction rather than in autocommit")
                                    .isTrue();

                            insertUser(
                                    userId);

                            assertThat(
                                    countUsers(
                                            userId))
                                    .as("the write of the scope must be visible inside its"
                                            + " own transaction")
                                    .isEqualTo(
                                            1);

                            throw failure;
                        }))
                .isSameAs(
                        failure);

        assertThat(
                countUsers(
                        userId))
                .as("database work performed inside a failed serialized scope must not"
                        + " remain committed")
                .isZero();
    }

    @Test
    void aFailedScopeReleasesSerializationForTheSameExternalIdentity()
            throws Exception {

        // Why: a failed provisioning attempt must not poison the external
        // identity it was provisioning.
        // Covers: a new scope for the same issuer/subject entering and
        // completing after the previous scope rolled back.
        // Prevents: a session-level advisory lock surviving rollback and
        // permanently blocking that external identity.

        var userId =
                UUID.randomUUID();

        var failure =
                new IllegalStateException(
                        "synthetic rolled back scope failure");

        assertThatThrownBy(
                () -> coordinator().executeSerialized(
                        ISSUER,
                        SUBJECT,
                        () -> {

                            insertUser(
                                    userId);

                            throw failure;
                        }))
                .isSameAs(
                        failure);

        var executor =
                Executors.newSingleThreadExecutor();

        try {

            Future<String> retry =
                    executor.submit(
                            () -> coordinator().executeSerialized(
                                    ISSUER,
                                    SUBJECT,
                                    () -> "recovered"));

            assertThat(
                    resultWithin(
                            retry,
                            "A serialized scope for the same external identity could not be"
                                    + " entered after the previous scope rolled back, so the"
                                    + " serialization outlived its transaction"))
                    .isEqualTo(
                            "recovered");

        } finally {

            shutdown(
                    executor);
        }

        assertThat(
                countUsers(
                        userId))
                .as("the rolled back scope must not leave committed User state")
                .isZero();
    }

    @Test
    void ambientTransactionRollbackAlsoUndoesWorkPerformedInsideTheScope() {

        // Why: the coordination scope promises to participate in an existing
        // transaction instead of committing independently.
        // Covers: an enclosing transaction rolling back after the scope already
        // returned successfully.
        // Prevents: an independent inner transaction leaving orphaned Users
        // state committed after the enclosing orchestration fails.

        var coordinator =
                coordinator();

        var userId =
                UUID.randomUUID();

        transactionTemplate.executeWithoutResult(
                outerStatus -> {

                    var result =
                            coordinator.executeSerialized(
                                    ISSUER,
                                    SUBJECT,
                                    () -> {

                                        insertUser(
                                                userId);

                                        return "inner";
                                    });

                    assertThat(result)
                            .isEqualTo(
                                    "inner");

                    assertThat(
                            countUsers(
                                    userId))
                            .as("the write of the scope must be visible inside the ambient"
                                    + " transaction")
                            .isEqualTo(
                                    1);

                    outerStatus.setRollbackOnly();
                });

        assertThat(
                countUsers(
                        userId))
                .as("the coordination scope must participate in the ambient transaction"
                        + " instead of committing independently")
                .isZero();
    }

    private ExternalIdentityUserProvisioningCoordinator coordinator() {

        return new PostgreSqlExternalIdentitySerializationCoordinator(
                jdbcTemplate,
                transactionManager);
    }

    private void insertUser(
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

    private int countUsers(
            UUID userId) {

        var count =
                jdbcTemplate.queryForObject(
                        """
                        SELECT count(*)
                        FROM users.users
                        WHERE id = ?
                        """,
                        Integer.class,
                        userId);

        if (count == null) {
            throw new AssertionError(
                    "PostgreSQL did not return a User count");
        }

        return count;
    }

    /**
     * Observes the lock view of PostgreSQL while a same-identity scope is
     * expected to be waiting. Thread coordination is latch-based; this bounded
     * poll only samples durable engine state that offers no notification
     * channel.
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
                    "External identity serialization executor did not terminate");
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
}
