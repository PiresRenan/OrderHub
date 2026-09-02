package io.github.piresrenan.orderhub.orders;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import io.github.piresrenan.orderhub.inventory.application.port.in.InventoryCommitmentRejectedException;

/**
 * OH-011 acceptance #12.
 *
 * <p>
 * Two independent OrderHub JVM processes, each with its own Spring context,
 * Hikari pool and transaction manager, compete for the same final Inventory
 * unit in one shared PostgreSQL database.
 * </p>
 */
@Testcontainers
class OrderHubMultiReplicaInventoryAcceptanceTest {

    private static final DockerImageName POSTGRES_IMAGE =
            DockerImageName.parse(
                    "postgres:18.6-trixie@sha256:"
                            + "4ef4dbc939d61acea57712655ddb4b4ab27419c913f94cca0cd57cb3ea3c2280")
                    .asCompatibleSubstituteFor("postgres");

    private static final UUID TENANT_ID =
            UUID.fromString(
                    "10000000-0000-0000-0000-000000000001");

    private static final UUID PRODUCT_ID =
            UUID.fromString(
                    "20000000-0000-0000-0000-000000000001");

    private static final UUID VARIANT_ID =
            UUID.fromString(
                    "30000000-0000-0000-0000-000000000001");

    private static final UUID CUSTOMER_A =
            UUID.fromString(
                    "50000000-0000-0000-0000-000000000001");

    private static final UUID CUSTOMER_B =
            UUID.fromString(
                    "50000000-0000-0000-0000-000000000002");

    private static final String WORKER_CLASS =
            "io.github.piresrenan.orderhub.orders.support."
                    + "OrderHubReplicaWorker";

    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(POSTGRES_IMAGE)
                    .withDatabaseName("orderhub_test")
                    .withUsername("orderhub_test")
                    .withPassword("synthetic-test-password");

    @TempDir
    Path temporaryDirectory;

    @Test
    void twoIndependentOrderHubProcessesCannotCommitTheSameLastUnitTwice()
            throws Exception {

        var fixtureDataSource =
                new DriverManagerDataSource(
                        POSTGRES.getJdbcUrl(),
                        POSTGRES.getUsername(),
                        POSTGRES.getPassword());

        Flyway.configure()
                .dataSource(fixtureDataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();

        var jdbcTemplate =
                new JdbcTemplate(
                        fixtureDataSource);

        resetBusinessState(
                jdbcTemplate);

        seedLastUnit(
                jdbcTemplate);

        var gateDirectory =
                temporaryDirectory.resolve(
                        "replica-gate");

        Files.createDirectories(
                gateDirectory);

        var logA =
                temporaryDirectory.resolve(
                        "replica-a.log");

        var logB =
                temporaryDirectory.resolve(
                        "replica-b.log");

        var replicaA =
                startReplica(
                        "A",
                        CUSTOMER_A,
                        gateDirectory,
                        logA);

        var replicaB =
                startReplica(
                        "B",
                        CUSTOMER_B,
                        gateDirectory,
                        logB);

        try {
            var readyA =
                    gateDirectory.resolve(
                            "ready-A");

            var readyB =
                    gateDirectory.resolve(
                            "ready-B");

            awaitReplicaReady(
                    replicaA,
                    readyA,
                    logA);

            awaitReplicaReady(
                    replicaB,
                    readyB,
                    logB);

            var evidenceA =
                    Files.readString(
                            readyA,
                            StandardCharsets.UTF_8);

            var evidenceB =
                    Files.readString(
                            readyB,
                            StandardCharsets.UTF_8);

            var pidA =
                    parsePid(evidenceA);

            var pidB =
                    parsePid(evidenceB);

            assertThat(pidA)
                    .isNotEqualTo(pidB)
                    .isNotEqualTo(
                            ProcessHandle.current().pid());

            assertThat(pidB)
                    .isNotEqualTo(
                            ProcessHandle.current().pid());

            assertThat(evidenceA)
                    .contains("pool=OrderHubReplica-A")
                    .contains(
                            "datasource=com.zaxxer.hikari.HikariDataSource");

            assertThat(evidenceB)
                    .contains("pool=OrderHubReplica-B")
                    .contains(
                            "datasource=com.zaxxer.hikari.HikariDataSource");

            Files.createFile(
                    gateDirectory.resolve("start"));

            assertProcessCompleted(
                    replicaA,
                    logA);

            assertProcessCompleted(
                    replicaB,
                    logB);

            var resultA =
                    Files.readString(
                            gateDirectory.resolve(
                                    "result-A"),
                            StandardCharsets.UTF_8);

            var resultB =
                    Files.readString(
                            gateDirectory.resolve(
                                    "result-B"),
                            StandardCharsets.UTF_8);

            var results =
                    List.of(
                            resultA,
                            resultB);

            assertThat(results)
                    .allMatch(result ->
                            result.equals("SUCCESS")
                                    || result.startsWith(
                                            "FAILURE:"));

            assertThat(
                    results.stream()
                            .filter("SUCCESS"::equals)
                            .count())
                    .isEqualTo(1);

            assertThat(
                    results.stream()
                            .filter(result ->
                                    result.startsWith(
                                            "FAILURE:"))
                            .count())
                    .isEqualTo(1);

            assertThat(results)
                    .filteredOn(result ->
                            result.startsWith(
                                    "FAILURE:"))
                    .containsExactly(
                            "FAILURE:"
                                    + InventoryCommitmentRejectedException.class
                                            .getName());

            assertDurableSingleCommit(
                    jdbcTemplate);
        } finally {
            destroyIfAlive(replicaA);
            destroyIfAlive(replicaB);
        }
    }

    private Process startReplica(
            String replicaName,
            UUID customerId,
            Path gateDirectory,
            Path logPath)
            throws Exception {

        var javaExecutable =
                Path.of(
                        System.getProperty("java.home"),
                        "bin",
                        isWindows()
                                ? "java.exe"
                                : "java")
                        .toString();

        var classPath =
                System.getProperty(
                        "surefire.test.class.path");

        if (classPath == null
                || classPath.isBlank()) {

            classPath =
                    System.getProperty(
                            "java.class.path");
        }

        if (classPath == null
                || classPath.isBlank()) {

            throw new IllegalStateException(
                    "Could not resolve child-JVM test classpath");
        }

        var processBuilder =
                new ProcessBuilder(
                        javaExecutable,
                        "-cp",
                        classPath,
                        WORKER_CLASS,
                        replicaName,
                        POSTGRES.getJdbcUrl(),
                        POSTGRES.getUsername(),
                        POSTGRES.getPassword(),
                        TENANT_ID.toString(),
                        customerId.toString(),
                        VARIANT_ID.toString(),
                        gateDirectory.toAbsolutePath().toString());

        processBuilder
                .redirectErrorStream(true)
                .redirectOutput(
                        logPath.toFile());

        return processBuilder.start();
    }

    private void awaitReplicaReady(
            Process process,
            Path readyPath,
            Path logPath)
            throws Exception {

        var deadline =
                System.nanoTime()
                        + Duration.ofSeconds(30).toNanos();

        while (!Files.exists(readyPath)) {

            if (!process.isAlive()) {
                throw new AssertionError(
                        "Replica exited before readiness. Exit="
                                + process.exitValue()
                                + System.lineSeparator()
                                + readLog(logPath));
            }

            if (System.nanoTime() >= deadline) {
                throw new AssertionError(
                        "Replica readiness timed out."
                                + System.lineSeparator()
                                + readLog(logPath));
            }

            Thread.sleep(50);
        }
    }

    private void assertProcessCompleted(
            Process process,
            Path logPath)
            throws Exception {

        var finished =
                process.waitFor(
                        20,
                        TimeUnit.SECONDS);

        assertThat(finished)
                .as(
                        "Replica must terminate after one Order attempt.%n%s",
                        readLog(logPath))
                .isTrue();

        if (!finished) {
            process.destroyForcibly();
            return;
        }

        assertThat(process.exitValue())
                .as(
                        "Replica JVM exit code.%n%s",
                        readLog(logPath))
                .isZero();
    }

    private static void resetBusinessState(
            JdbcTemplate jdbcTemplate) {

        jdbcTemplate.update("""
                TRUNCATE TABLE
                    inventory.inventory_commitments,
                    inventory.inventory_positions,
                    inventory.tenant_policies,
                    catalog.media,
                    catalog.variant_base_prices,
                    catalog.product_variant_attributes,
                    catalog.product_categories,
                    catalog.product_variants,
                    catalog.categories,
                    catalog.category_hierarchy_guards,
                    catalog.products,
                    orders.order_request_idempotency,
                    orders.order_items,
                    orders.orders
                """);
    }

    private static void seedLastUnit(
            JdbcTemplate jdbcTemplate) {

        jdbcTemplate.update("""
                INSERT INTO catalog.products (
                    tenant_id,
                    id,
                    name,
                    slug,
                    description,
                    status
                )
                VALUES (
                    ?,
                    ?,
                    'Multi replica fixture product',
                    'multi_replica_fixture_product',
                    NULL,
                    'ACTIVE'
                )
                """,
                TENANT_ID,
                PRODUCT_ID);

        jdbcTemplate.update("""
                INSERT INTO catalog.product_variants (
                    tenant_id,
                    id,
                    product_id,
                    sku,
                    status
                )
                VALUES (
                    ?,
                    ?,
                    ?,
                    'MULTI-REPLICA-VARIANT',
                    'ACTIVE'
                )
                """,
                TENANT_ID,
                VARIANT_ID,
                PRODUCT_ID);

        jdbcTemplate.update("""
                INSERT INTO inventory.tenant_policies (
                    tenant_id,
                    policy
                )
                VALUES (?, 'DENY')
                """,
                TENANT_ID);

        jdbcTemplate.update("""
                INSERT INTO inventory.inventory_positions (
                    tenant_id,
                    variant_id,
                    on_hand,
                    committed,
                    backordered,
                    safety_stock
                )
                VALUES (?, ?, 1, 0, 0, 0)
                """,
                TENANT_ID,
                VARIANT_ID);
    }

    private static void assertDurableSingleCommit(
            JdbcTemplate jdbcTemplate) {

        assertThat(
                scalar(
                        jdbcTemplate,
                        """
                        SELECT COUNT(*)
                        FROM orders.orders
                        WHERE tenant_id = ?
                        """,
                        TENANT_ID))
                .isEqualTo(1);

        assertThat(
                scalar(
                        jdbcTemplate,
                        """
                        SELECT COUNT(*)
                        FROM orders.order_items
                        WHERE tenant_id = ?
                        """,
                        TENANT_ID))
                .isEqualTo(1);

        assertThat(
                scalar(
                        jdbcTemplate,
                        """
                        SELECT committed
                        FROM inventory.inventory_positions
                        WHERE tenant_id = ?
                          AND variant_id = ?
                        """,
                        TENANT_ID,
                        VARIANT_ID))
                .isEqualTo(1);

        assertThat(
                scalar(
                        jdbcTemplate,
                        """
                        SELECT backordered
                        FROM inventory.inventory_positions
                        WHERE tenant_id = ?
                          AND variant_id = ?
                        """,
                        TENANT_ID,
                        VARIANT_ID))
                .isZero();

        assertThat(
                scalar(
                        jdbcTemplate,
                        """
                        SELECT COUNT(*)
                        FROM inventory.inventory_commitments
                        WHERE tenant_id = ?
                          AND variant_id = ?
                        """,
                        TENANT_ID,
                        VARIANT_ID))
                .isEqualTo(1);

        assertThat(
                scalar(
                        jdbcTemplate,
                        """
                        SELECT COALESCE(
                            SUM(allocated_quantity),
                            0)
                        FROM inventory.inventory_commitments
                        WHERE tenant_id = ?
                          AND variant_id = ?
                        """,
                        TENANT_ID,
                        VARIANT_ID))
                .isEqualTo(1);
    }

    private static long scalar(
            JdbcTemplate jdbcTemplate,
            String sql,
            Object... arguments) {

        return jdbcTemplate.queryForObject(
                sql,
                Long.class,
                arguments);
    }

    private static long parsePid(
            String readinessEvidence) {

        return readinessEvidence.lines()
                .filter(line ->
                        line.startsWith("pid="))
                .map(line ->
                        line.substring(
                                "pid=".length()))
                .mapToLong(Long::parseLong)
                .findFirst()
                .orElseThrow(() ->
                        new IllegalStateException(
                                "Replica PID evidence is missing"));
    }

    private static String readLog(
            Path logPath)
            throws Exception {

        if (!Files.exists(logPath)) {
            return "<no replica log>";
        }

        return Files.readString(
                logPath,
                StandardCharsets.UTF_8);
    }

    private static void destroyIfAlive(
            Process process)
            throws Exception {

        if (process == null
                || !process.isAlive()) {
            return;
        }

        process.destroy();

        if (!process.waitFor(
                5,
                TimeUnit.SECONDS)) {

            process.destroyForcibly();
            process.waitFor(
                    5,
                    TimeUnit.SECONDS);
        }
    }

    private static boolean isWindows() {

        return System.getProperty(
                "os.name",
                "")
                .toLowerCase()
                .contains("win");
    }
}
