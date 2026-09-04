package io.github.piresrenan.orderhub.customers.adapter.out.persistence.postgresql;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

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

import io.github.piresrenan.orderhub.customers.application.port.out.CustomerAccountBindingRepository;

@Testcontainers
class PostgreSqlCustomerAccountBindingRepositoryTest {

    private static final DockerImageName POSTGRES_IMAGE =
            DockerImageName.parse(
                    "postgres:18.6-trixie@sha256:"
                            + "4ef4dbc939d61acea57712655ddb4b4ab27419c913f94cca0cd57cb3ea3c2280")
                    .asCompatibleSubstituteFor(
                            "postgres");

    private static final UUID TENANT_A =
            UUID.fromString(
                    "10000000-0000-0000-0000-000000000001");

    private static final UUID TENANT_B =
            UUID.fromString(
                    "10000000-0000-0000-0000-000000000002");

    private static final UUID CUSTOMER_A =
            UUID.fromString(
                    "20000000-0000-0000-0000-000000000001");

    private static final UUID CUSTOMER_B =
            UUID.fromString(
                    "20000000-0000-0000-0000-000000000002");

    private static final UUID USER_A =
            UUID.fromString(
                    "30000000-0000-0000-0000-000000000001");

    private static final UUID USER_B =
            UUID.fromString(
                    "30000000-0000-0000-0000-000000000002");

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

    private static CustomerAccountBindingRepository repository;

    @BeforeAll
    static void migrateSchemaAndCreateRepository() {

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

        repository =
                new PostgreSqlCustomerAccountBindingRepository(
                        jdbcTemplate);
    }

    @BeforeEach
    void resetAndCreateAdversarialRelationships() {

        jdbcTemplate.update("""
                TRUNCATE TABLE
                    customers.customer_account_bindings,
                    customers.customer_profiles
                """);

        /*
         * Exact authority under test:
         *
         *     Tenant A / Customer A / User A
         *
         * The two additional relationships ensure that each mismatching
         * identity still participates in nearby valid tuples. A query that
         * accidentally omits any one predicate would therefore return a
         * false positive.
         */
        insertProfile(
                TENANT_A,
                CUSTOMER_A);

        insertProfile(
                TENANT_A,
                CUSTOMER_B);

        insertProfile(
                TENANT_B,
                CUSTOMER_A);

        insertBinding(
                TENANT_A,
                CUSTOMER_A,
                USER_A);

        insertBinding(
                TENANT_A,
                CUSTOMER_B,
                USER_B);

        insertBinding(
                TENANT_B,
                CUSTOMER_A,
                USER_B);
    }

    @Test
    void returnsTrueOnlyForPersistedExactBinding() {

        assertThat(
                repository.existsExact(
                        TENANT_A,
                        CUSTOMER_A,
                        USER_A))
                .isTrue();
    }

    @Test
    void doesNotInheritBindingFromAnotherTenant() {

        assertThat(
                repository.existsExact(
                        TENANT_B,
                        CUSTOMER_A,
                        USER_A))
                .isFalse();
    }

    @Test
    void doesNotInheritBindingFromAnotherCustomer() {

        assertThat(
                repository.existsExact(
                        TENANT_A,
                        CUSTOMER_B,
                        USER_A))
                .isFalse();
    }

    @Test
    void doesNotInheritBindingFromAnotherUser() {

        assertThat(
                repository.existsExact(
                        TENANT_A,
                        CUSTOMER_A,
                        USER_B))
                .isFalse();
    }

    private static void insertProfile(
            UUID tenantId,
            UUID customerId) {

        jdbcTemplate.update("""
                INSERT INTO customers.customer_profiles (
                    tenant_id,
                    customer_id
                )
                VALUES (?, ?)
                """,
                tenantId,
                customerId);
    }

    private static void insertBinding(
            UUID tenantId,
            UUID customerId,
            UUID userId) {

        jdbcTemplate.update("""
                INSERT INTO customers.customer_account_bindings (
                    tenant_id,
                    customer_id,
                    user_id
                )
                VALUES (?, ?, ?)
                """,
                tenantId,
                customerId,
                userId);
    }
}
