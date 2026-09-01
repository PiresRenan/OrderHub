package io.github.piresrenan.orderhub.inventory.adapter.out.persistence.postgresql;

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

import io.github.piresrenan.orderhub.inventory.application.port.out.InventoryPolicyRepository;
import io.github.piresrenan.orderhub.inventory.domain.model.InventoryPolicy;

@Testcontainers
class PostgreSqlInventoryPolicyRepositoryTest {

    private static final DockerImageName POSTGRES_IMAGE =
            DockerImageName.parse(
                    "postgres:18.6-trixie@sha256:"
                            + "4ef4dbc939d61acea57712655ddb4b4ab27419c913f94cca0cd57cb3ea3c2280")
                    .asCompatibleSubstituteFor("postgres");

    private static final UUID TENANT_A =
            UUID.fromString(
                    "11111111-1111-1111-1111-111111111111");

    private static final UUID TENANT_B =
            UUID.fromString(
                    "22222222-2222-2222-2222-222222222222");

    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(POSTGRES_IMAGE)
                    .withDatabaseName("orderhub_test")
                    .withUsername("orderhub_test")
                    .withPassword("synthetic-test-password");

    private static JdbcTemplate jdbcTemplate;

    private static InventoryPolicyRepository repository;

    @BeforeAll
    static void migrateSchemaAndCreateRepository() {

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
                new JdbcTemplate(dataSource);

        repository =
                new PostgreSqlInventoryPolicyRepository(
                        jdbcTemplate);
    }

    @BeforeEach
    void cleanPolicies() {

        jdbcTemplate.update("""
                TRUNCATE TABLE
                    inventory.tenant_policies
                """);
    }

    @Test
    void findsDenyPolicyForTenant() {

        insertPolicy(
                TENANT_A,
                "DENY");

        assertThat(
                repository.findByTenantId(
                        TENANT_A))
                .contains(
                        InventoryPolicy.DENY);
    }

    @Test
    void findsAllowBackorderPolicyForTenant() {

        insertPolicy(
                TENANT_A,
                "ALLOW_BACKORDER");

        assertThat(
                repository.findByTenantId(
                        TENANT_A))
                .contains(
                        InventoryPolicy.ALLOW_BACKORDER);
    }

    @Test
    void returnsEmptyWhenTenantHasNoInventoryPolicy() {

        assertThat(
                repository.findByTenantId(
                        TENANT_A))
                .isEmpty();
    }

    @Test
    void isolatesPoliciesAcrossTenants() {

        insertPolicy(
                TENANT_A,
                "DENY");

        insertPolicy(
                TENANT_B,
                "ALLOW_BACKORDER");

        assertThat(
                repository.findByTenantId(
                        TENANT_A))
                .contains(
                        InventoryPolicy.DENY);

        assertThat(
                repository.findByTenantId(
                        TENANT_B))
                .contains(
                        InventoryPolicy.ALLOW_BACKORDER);
    }

    private static void insertPolicy(
            UUID tenantId,
            String policy) {

        jdbcTemplate.update("""
                INSERT INTO inventory.tenant_policies (
                    tenant_id,
                    policy
                )
                VALUES (?, ?)
                """,
                tenantId,
                policy);
    }
}