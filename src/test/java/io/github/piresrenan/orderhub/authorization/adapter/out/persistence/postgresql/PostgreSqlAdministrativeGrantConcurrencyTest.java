package io.github.piresrenan.orderhub.authorization.adapter.out.persistence.postgresql;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

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

import io.github.piresrenan.orderhub.authorization.application.port.out.AdministrativeGrantMutationResult;
import io.github.piresrenan.orderhub.authorization.domain.model.AdministrativeGrant;
import io.github.piresrenan.orderhub.authorization.domain.model.AdministrativeScope;
import io.github.piresrenan.orderhub.authorization.domain.model.PermissionCode;

@Testcontainers
class PostgreSqlAdministrativeGrantConcurrencyTest {

    private static final DockerImageName POSTGRES_IMAGE =
            DockerImageName.parse(
                    "postgres:18.6-trixie@sha256:"
                            + "4ef4dbc939d61acea57712655ddb4b4ab27419c913f94cca0cd57cb3ea3c2280")
                    .asCompatibleSubstituteFor("postgres");

    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(POSTGRES_IMAGE)
                    .withDatabaseName("orderhub_test")
                    .withUsername("orderhub_test")
                    .withPassword("synthetic-test-password");

    private static JdbcTemplate jdbcTemplate;
    private static PostgreSqlAdministrativeGrantRepository repository;

    @BeforeAll
    static void migrateSchema() {

        var dataSource =
                new DriverManagerDataSource(
                        POSTGRES.getJdbcUrl(),
                        POSTGRES.getUsername(),
                        POSTGRES.getPassword());

        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();

        jdbcTemplate =
                new JdbcTemplate(
                        dataSource);

        repository =
                new PostgreSqlAdministrativeGrantRepository(
                        jdbcTemplate);
    }

    @BeforeEach
    void clearGrants() {

        jdbcTemplate.update(
                "DELETE FROM access_control.administrative_grants");
    }

    @Test
    void concurrentDuplicateGrantHasOneInsertAndOneIdempotentOutcome() {

        var grant =
                platformGrant();

        var results =
                executeConcurrently(
                        () ->
                                repository.grant(
                                        grant),
                        () ->
                                repository.grant(
                                        grant));

        assertThat(results)
                .containsExactlyInAnyOrder(
                        AdministrativeGrantMutationResult.GRANTED,
                        AdministrativeGrantMutationResult.ALREADY_GRANTED);

        assertThat(
                countGrantRows())
                .isEqualTo(
                        1);
    }

    @Test
    void concurrentDuplicateRevokeHasOneDeleteAndOneIdempotentOutcome() {

        var grant =
                platformGrant();

        repository.grant(
                grant);

        var results =
                executeConcurrently(
                        () ->
                                repository.revoke(
                                        grant),
                        () ->
                                repository.revoke(
                                        grant));

        assertThat(results)
                .containsExactlyInAnyOrder(
                        AdministrativeGrantMutationResult.REVOKED,
                        AdministrativeGrantMutationResult.ALREADY_ABSENT);

        assertThat(
                countGrantRows())
                .isZero();
    }

    private static Set<AdministrativeGrantMutationResult> executeConcurrently(
            Callable<AdministrativeGrantMutationResult> first,
            Callable<AdministrativeGrantMutationResult> second) {

        var barrier =
                new CyclicBarrier(
                        2);

        try (var executor =
                Executors.newFixedThreadPool(
                        2)) {

            var firstFuture =
                    executor.submit(() -> {
                        barrier.await(
                                10,
                                TimeUnit.SECONDS);

                        return first.call();
                    });

            var secondFuture =
                    executor.submit(() -> {
                        barrier.await(
                                10,
                                TimeUnit.SECONDS);

                        return second.call();
                    });

            return Set.of(
                    firstFuture.get(
                            15,
                            TimeUnit.SECONDS),
                    secondFuture.get(
                            15,
                            TimeUnit.SECONDS));

        } catch (Exception exception) {

            throw new AssertionError(
                    "Concurrent administrative grant mutation failed",
                    exception);
        }
    }

    private static AdministrativeGrant platformGrant() {

        return new AdministrativeGrant(
                UUID.randomUUID(),
                AdministrativeScope.platform(),
                PermissionCode.PLATFORM_ORGANIZATIONS_VIEW);
    }

    private static int countGrantRows() {

        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM access_control.administrative_grants",
                Integer.class);
    }
}
