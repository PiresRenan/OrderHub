package io.github.piresrenan.orderhub.orders.support;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import javax.sql.DataSource;

import com.zaxxer.hikari.HikariDataSource;

import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.transaction.PlatformTransactionManager;

import io.github.piresrenan.orderhub.OrderHubApplication;
import io.github.piresrenan.orderhub.orders.application.port.in.CreateOrderCommand;
import io.github.piresrenan.orderhub.orders.application.port.in.CreateOrderIdempotencyKeyDigest;
import io.github.piresrenan.orderhub.orders.application.port.in.CreateOrderUseCase;

/**
 * Test-only executable used by the multi-replica acceptance test.
 *
 * <p>
 * Each invocation runs in its own JVM and boots the real OrderHub
 * application against the PostgreSQL instance supplied by the parent test.
 * </p>
 */
public final class OrderHubReplicaWorker {

    private OrderHubReplicaWorker() {
    }

    public static void main(
            String[] args)
            throws Exception {

        if (args.length != 8) {
            throw new IllegalArgumentException(
                    "Expected 8 replica-worker arguments");
        }

        var replicaName =
                args[0];

        var jdbcUrl =
                args[1];

        var username =
                args[2];

        var password =
                args[3];

        var tenantId =
                UUID.fromString(args[4]);

        var customerId =
                UUID.fromString(args[5]);

        var variantId =
                UUID.fromString(args[6]);

        var gateDirectory =
                Path.of(args[7]);

        var readyPath =
                gateDirectory.resolve(
                        "ready-" + replicaName);

        var resultPath =
                gateDirectory.resolve(
                        "result-" + replicaName);

        var startPath =
                gateDirectory.resolve(
                        "start");

        try (var context =
                new SpringApplicationBuilder(
                        OrderHubApplication.class)
                        .web(WebApplicationType.SERVLET)
                        .properties(
                                "server.port=0",
                                "spring.flyway.enabled=false",
                                "spring.datasource.url=" + jdbcUrl,
                                "spring.datasource.username=" + username,
                                "spring.datasource.password=" + password,
                                "spring.datasource.hikari.pool-name="
                                        + "OrderHubReplica-" + replicaName,
                                "spring.jmx.enabled=false",
                                "spring.main.banner-mode=off",
                                "logging.level.root=WARN",
                                "orderhub.orders.transaction.timeout=5s",
                                "orderhub.security.jwt.issuer="
                                        + "https://issuer.orderhub.test",
                                "orderhub.security.jwt.audience="
                                        + "orderhub-api-test",
                                "orderhub.security.jwt.jwk-set-uri="
                                        + "http://127.0.0.1:1/test-only-jwks")
                        .run()) {

            var dataSource =
                    context.getBean(
                            DataSource.class);

            if (!(dataSource instanceof HikariDataSource hikariDataSource)) {
                throw new IllegalStateException(
                        "Replica is not using its own HikariDataSource");
            }

            context.getBean(
                    PlatformTransactionManager.class);

            var createOrder =
                    context.getBean(
                            CreateOrderUseCase.class);

            var readinessEvidence =
                    "pid="
                            + ProcessHandle.current().pid()
                            + System.lineSeparator()
                            + "pool="
                            + hikariDataSource.getPoolName()
                            + System.lineSeparator()
                            + "datasource="
                            + dataSource.getClass().getName();

            Files.writeString(
                    readyPath,
                    readinessEvidence,
                    StandardCharsets.UTF_8);

            awaitStartGate(
                    startPath);

            try {
                createOrder.create(
                        new CreateOrderCommand(
                                tenantId,
                                customerId,
                                List.of(
                                        new CreateOrderCommand.Item(
                                                variantId,
                                                1)),
                                TestCreateOrderIdempotencyKeyDigests.from(
                                        "multi-replica:"
                                                + customerId)));

                Files.writeString(
                        resultPath,
                        "SUCCESS",
                        StandardCharsets.UTF_8);
            } catch (Throwable failure) {

                Files.writeString(
                        resultPath,
                        "FAILURE:"
                                + failure.getClass().getName(),
                        StandardCharsets.UTF_8);
            }
        } catch (Throwable failure) {

            try {
                Files.writeString(
                        resultPath,
                        "BOOT_FAILURE:"
                                + failure.getClass().getName(),
                        StandardCharsets.UTF_8);
            } catch (Throwable ignored) {
                // The process exit code remains the authoritative boot signal.
            }

            failure.printStackTrace(System.err);
            System.exit(2);
        }
    }

    private static void awaitStartGate(
            Path startPath)
            throws Exception {

        var deadline =
                System.nanoTime()
                        + Duration.ofSeconds(30).toNanos();

        while (!Files.exists(startPath)) {

            if (System.nanoTime() >= deadline) {
                throw new IllegalStateException(
                        "Replica start gate timed out");
            }

            Thread.sleep(25);
        }
    }
}