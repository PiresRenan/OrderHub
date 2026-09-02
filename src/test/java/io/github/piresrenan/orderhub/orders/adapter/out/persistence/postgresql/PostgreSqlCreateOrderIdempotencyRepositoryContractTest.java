package io.github.piresrenan.orderhub.orders.adapter.out.persistence.postgresql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.InvocationTargetException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import io.github.piresrenan.orderhub.orders.application.idempotency.CreateOrderRequestFingerprint;
import io.github.piresrenan.orderhub.orders.application.port.in.CreateOrderAllocationOutcome;
import io.github.piresrenan.orderhub.orders.application.port.in.CreateOrderCommand;
import io.github.piresrenan.orderhub.orders.application.port.in.CreateOrderIdempotencyKeyDigest;
import io.github.piresrenan.orderhub.orders.domain.model.OrderStatus;

@Testcontainers
class PostgreSqlCreateOrderIdempotencyRepositoryContractTest {

    private static final String REPOSITORY_TYPE =
            "io.github.piresrenan.orderhub.orders.adapter.out.persistence.postgresql.PostgreSqlCreateOrderIdempotencyRepository";

    private static final String COMPLETION_TYPE =
            "io.github.piresrenan.orderhub.orders.application.port.out.CreateOrderIdempotencyCompletion";

    private static final DockerImageName POSTGRES_IMAGE =
            DockerImageName.parse(
                    "postgres:18.6-trixie@sha256:"
                            + "4ef4dbc939d61acea57712655ddb4b4ab27419c913f94cca0cd57cb3ea3c2280")
                    .asCompatibleSubstituteFor(
                            "postgres");

    private static final UUID TENANT_A =
            UUID.fromString(
                    "11111111-1111-1111-1111-111111111111");

    private static final UUID TENANT_B =
            UUID.fromString(
                    "22222222-2222-2222-2222-222222222222");

    private static final UUID CUSTOMER_ID =
            UUID.fromString(
                    "33333333-3333-3333-3333-333333333333");

    private static final UUID VARIANT_ID =
            UUID.fromString(
                    "44444444-4444-4444-4444-444444444444");

    private static final UUID ORDER_ID =
            UUID.fromString(
                    "55555555-5555-5555-5555-555555555555");

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

    private static DriverManagerDataSource dataSource;
    private static JdbcTemplate jdbcTemplate;

    @BeforeAll
    static void migrateSchema() {

        dataSource =
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
    void cleanIdempotencyState() {

        jdbcTemplate.update(
                """
                TRUNCATE TABLE
                    orders.order_request_idempotency
                """);
    }

    @Test
    void acquiresCompletesReplaysAndDetectsFingerprintConflict() {

        var repository =
                repository(
                        Duration.ofSeconds(
                                2));

        transaction().execute(status -> {

            var acquired =
                    acquire(
                            repository,
                            TENANT_A,
                            (byte) 1,
                            2);

            assertThat(acquired.getClass().getSimpleName())
                    .isEqualTo(
                            "Acquired");

            complete(
                    repository,
                    TENANT_A,
                    (byte) 1,
                    2);

            return null;
        });

        var replay =
                transaction().execute(status ->
                        acquire(
                                repository,
                                TENANT_A,
                                (byte) 1,
                                2));

        assertThat(replay.getClass().getSimpleName())
                .isEqualTo(
                        "Replay");

        var replayCompletion =
                accessor(
                        replay,
                        "completion");

        assertThat(
                accessor(
                        replayCompletion,
                        "orderId"))
                .isEqualTo(
                        ORDER_ID);

        assertThat(
                accessor(
                        replayCompletion,
                        "orderStatus"))
                .isEqualTo(
                        OrderStatus.CREATED);

        assertThat(
                accessor(
                        replayCompletion,
                        "allocationOutcome"))
                .isEqualTo(
                        CreateOrderAllocationOutcome.FULLY_ALLOCATED);

        var conflict =
                transaction().execute(status ->
                        acquire(
                                repository,
                                TENANT_A,
                                (byte) 1,
                                3));

        assertThat(conflict.getClass().getSimpleName())
                .isEqualTo(
                        "FingerprintConflict");

        transaction().execute(status -> {

            var independentTenant =
                    acquire(
                            repository,
                            TENANT_B,
                            (byte) 1,
                            2);

            assertThat(independentTenant.getClass().getSimpleName())
                    .isEqualTo(
                            "Acquired");

            complete(
                    repository,
                    TENANT_B,
                    (byte) 1,
                    2);

            return null;
        });

        assertThat(processingRows())
                .isZero();
    }

    @Test
    void restoresPreviousLockTimeoutAfterAcquisition() {

        var repository =
                repository(
                        Duration.ofMillis(
                                100));

        transaction().execute(status -> {

            jdbcTemplate.queryForObject(
                    """
                    SELECT set_config(
                        'lock_timeout',
                        '1700ms',
                        true
                    )
                    """,
                    String.class);

            var before =
                    jdbcTemplate.queryForObject(
                            """
                            SELECT current_setting(
                                'lock_timeout'
                            )
                            """,
                            String.class);

            var acquired =
                    acquire(
                            repository,
                            TENANT_A,
                            (byte) 2,
                            2);

            assertThat(acquired.getClass().getSimpleName())
                    .isEqualTo(
                            "Acquired");

            var after =
                    jdbcTemplate.queryForObject(
                            """
                            SELECT current_setting(
                                'lock_timeout'
                            )
                            """,
                            String.class);

            assertThat(after)
                    .isEqualTo(
                            before);

            complete(
                    repository,
                    TENANT_A,
                    (byte) 2,
                    2);

            return null;
        });

        assertThat(processingRows())
                .isZero();
    }

    @Test
    void waiterReplaysAfterConcurrentOwnerCommits()
            throws Exception {

        var repository =
                repository(
                        Duration.ofSeconds(
                                2));

        var ownerAcquired =
                new CountDownLatch(
                        1);

        var releaseOwner =
                new CountDownLatch(
                        1);

        var executor =
                Executors.newFixedThreadPool(
                        2);

        try {

            var owner =
                    executor.submit(() -> {

                        transaction().execute(status -> {

                            var acquired =
                                    acquire(
                                            repository,
                                            TENANT_A,
                                            (byte) 3,
                                            2);

                            assertThat(acquired.getClass().getSimpleName())
                                    .isEqualTo(
                                            "Acquired");

                            ownerAcquired.countDown();

                            await(
                                    releaseOwner);

                            complete(
                                    repository,
                                    TENANT_A,
                                    (byte) 3,
                                    2);

                            return null;
                        });

                        return null;
                    });

            await(
                    ownerAcquired);

            var waiter =
                    executor.submit(() ->
                            transaction().execute(status ->
                                    acquire(
                                            repository,
                                            TENANT_A,
                                            (byte) 3,
                                            2)));

            briefSchedulingWindow();

            assertThat(waiter.isDone())
                    .as(
                            "Waiter should still be arbitrating against the open owner transaction")
                    .isFalse();

            releaseOwner.countDown();

            owner.get(
                    5,
                    TimeUnit.SECONDS);

            var waiterResult =
                    waiter.get(
                            5,
                            TimeUnit.SECONDS);

            assertThat(waiterResult.getClass().getSimpleName())
                    .isEqualTo(
                            "Replay");

            assertThat(processingRows())
                    .isZero();

        } finally {
            releaseOwner.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void waiterAcquiresAfterConcurrentOwnerRollsBack()
            throws Exception {

        var repository =
                repository(
                        Duration.ofSeconds(
                                2));

        var ownerAcquired =
                new CountDownLatch(
                        1);

        var releaseOwner =
                new CountDownLatch(
                        1);

        var executor =
                Executors.newFixedThreadPool(
                        2);

        try {

            var owner =
                    executor.submit(() -> {

                        transaction().execute(status -> {

                            var acquired =
                                    acquire(
                                            repository,
                                            TENANT_A,
                                            (byte) 4,
                                            2);

                            assertThat(acquired.getClass().getSimpleName())
                                    .isEqualTo(
                                            "Acquired");

                            ownerAcquired.countDown();

                            await(
                                    releaseOwner);

                            throw new IllegalStateException(
                                    "synthetic-owner-rollback");
                        });

                        return null;
                    });

            await(
                    ownerAcquired);

            var waiter =
                    executor.submit(() ->
                            transaction().execute(status -> {

                                var acquired =
                                        acquire(
                                                repository,
                                                TENANT_A,
                                                (byte) 4,
                                                2);

                                if (acquired.getClass().getSimpleName()
                                        .equals(
                                                "Acquired")) {

                                    complete(
                                            repository,
                                            TENANT_A,
                                            (byte) 4,
                                            2);
                                }

                                return acquired;
                            }));

            briefSchedulingWindow();

            assertThat(waiter.isDone())
                    .isFalse();

            releaseOwner.countDown();

            assertThatThrownBy(() ->
                    owner.get(
                            5,
                            TimeUnit.SECONDS))
                    .isInstanceOf(
                            ExecutionException.class)
                    .hasCauseInstanceOf(
                            IllegalStateException.class);

            var waiterResult =
                    waiter.get(
                            5,
                            TimeUnit.SECONDS);

            assertThat(waiterResult.getClass().getSimpleName())
                    .isEqualTo(
                            "Acquired");

            assertThat(processingRows())
                    .isZero();

        } finally {
            releaseOwner.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void boundsOnlyConflictingAcquisitionWait()
            throws Exception {

        var ownerRepository =
                repository(
                        Duration.ofSeconds(
                                2));

        var waiterRepository =
                repository(
                        Duration.ofMillis(
                                150));

        var ownerAcquired =
                new CountDownLatch(
                        1);

        var releaseOwner =
                new CountDownLatch(
                        1);

        var executor =
                Executors.newFixedThreadPool(
                        2);

        try {

            var owner =
                    executor.submit(() -> {

                        transaction().execute(status -> {

                            var acquired =
                                    acquire(
                                            ownerRepository,
                                            TENANT_A,
                                            (byte) 5,
                                            2);

                            assertThat(acquired.getClass().getSimpleName())
                                    .isEqualTo(
                                            "Acquired");

                            ownerAcquired.countDown();

                            await(
                                    releaseOwner);

                            complete(
                                    ownerRepository,
                                    TENANT_A,
                                    (byte) 5,
                                    2);

                            return null;
                        });

                        return null;
                    });

            await(
                    ownerAcquired);

            var waiter =
                    executor.submit(() ->
                            transaction().execute(status ->
                                    acquire(
                                            waiterRepository,
                                            TENANT_A,
                                            (byte) 5,
                                            2)));

            assertThatThrownBy(() ->
                    waiter.get(
                            3,
                            TimeUnit.SECONDS))
                    .isInstanceOf(
                            ExecutionException.class)
                    .satisfies(thrown ->
                            assertThat(
                                    thrown.getCause()
                                            .getClass()
                                            .getSimpleName())
                                    .isEqualTo(
                                            "CreateOrderIdempotencyInProgressException"));

            releaseOwner.countDown();

            owner.get(
                    5,
                    TimeUnit.SECONDS);

            assertThat(processingRows())
                    .isZero();

        } finally {
            releaseOwner.countDown();
            executor.shutdownNow();
        }
    }

    private static Object repository(
            Duration acquisitionTimeout) {

        var type =
                loadType(
                        REPOSITORY_TYPE);

        assertThat(type)
                .as(
                        "PostgreSqlCreateOrderIdempotencyRepository must exist")
                .isNotNull();

        try {
            return type
                    .getConstructor(
                            JdbcTemplate.class,
                            Duration.class)
                    .newInstance(
                            jdbcTemplate,
                            acquisitionTimeout);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(
                    exception);
        }
    }

    private static Object acquire(
            Object repository,
            UUID tenantId,
            byte keyMarker,
            int quantity) {

        try {

            var method =
                    repository.getClass()
                            .getMethod(
                                    "acquire",
                                    UUID.class,
                                    CreateOrderIdempotencyKeyDigest.class,
                                    CreateOrderRequestFingerprint.class);

            return invoke(
                    repository,
                    method,
                    tenantId,
                    CreateOrderIdempotencyKeyDigest.of(
                            digest(
                                    32,
                                    keyMarker)),
                    fingerprint(
                            tenantId,
                            quantity));

        } catch (NoSuchMethodException exception) {
            throw new AssertionError(
                    exception);
        }
    }

    private static void complete(
            Object repository,
            UUID tenantId,
            byte keyMarker,
            int quantity) {

        var completionType =
                loadType(
                        COMPLETION_TYPE);

        assertThat(completionType)
                .isNotNull();

        try {

            var completion =
                    completionType
                            .getConstructor(
                                    UUID.class,
                                    OrderStatus.class,
                                    CreateOrderAllocationOutcome.class)
                            .newInstance(
                                    ORDER_ID,
                                    OrderStatus.CREATED,
                                    CreateOrderAllocationOutcome.FULLY_ALLOCATED);

            var method =
                    repository.getClass()
                            .getMethod(
                                    "complete",
                                    UUID.class,
                                    CreateOrderIdempotencyKeyDigest.class,
                                    CreateOrderRequestFingerprint.class,
                                    completionType);

            invoke(
                    repository,
                    method,
                    tenantId,
                    CreateOrderIdempotencyKeyDigest.of(
                            digest(
                                    32,
                                    keyMarker)),
                    fingerprint(
                            tenantId,
                            quantity),
                    completion);

        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(
                    exception);
        }
    }

    private static CreateOrderRequestFingerprint fingerprint(
            UUID tenantId,
            int quantity) {

        return CreateOrderRequestFingerprint.from(
                new CreateOrderCommand(
                        tenantId,
                        CUSTOMER_ID,
                        List.of(
                                new CreateOrderCommand.Item(
                                        VARIANT_ID,
                                        quantity)),
                        CreateOrderIdempotencyKeyDigest.of(
                                digest(
                                        32,
                                        (byte) 99))));
    }

    private static TransactionTemplate transaction() {

        var transaction =
                new TransactionTemplate(
                        new DataSourceTransactionManager(
                                dataSource));

        transaction.setTimeout(
                5);

        return transaction;
    }

    private static int processingRows() {

        return jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM orders.order_request_idempotency
                WHERE state = 'PROCESSING'
                """,
                Integer.class);
    }

    private static Object accessor(
            Object target,
            String methodName) {

        try {
            return target
                    .getClass()
                    .getMethod(
                            methodName)
                    .invoke(
                            target);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(
                    exception);
        }
    }

    private static Object invoke(
            Object target,
            java.lang.reflect.Method method,
            Object... arguments) {

        try {
            return method.invoke(
                    target,
                    arguments);
        } catch (InvocationTargetException exception) {

            var cause =
                    exception.getCause();

            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }

            if (cause instanceof Error error) {
                throw error;
            }

            throw new AssertionError(
                    cause);

        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(
                    exception);
        }
    }

    private static Class<?> loadType(
            String name) {

        try {
            return Class.forName(
                    name);
        } catch (ClassNotFoundException ignored) {
            return null;
        }
    }

    private static byte[] digest(
            int length,
            byte marker) {

        var bytes =
                new byte[length];

        bytes[0] =
                marker;

        return bytes;
    }

    private static void await(
            CountDownLatch latch) {

        try {

            assertThat(
                    latch.await(
                            5,
                            TimeUnit.SECONDS))
                    .isTrue();

        } catch (InterruptedException exception) {
            Thread.currentThread()
                    .interrupt();

            throw new AssertionError(
                    exception);
        }
    }

    private static void briefSchedulingWindow() {

        try {
            Thread.sleep(
                    100);
        } catch (InterruptedException exception) {
            Thread.currentThread()
                    .interrupt();

            throw new AssertionError(
                    exception);
        }
    }
}
