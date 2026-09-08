package io.github.piresrenan.orderhub.inventory.adapter.out.persistence.postgresql;

import static org.assertj.core.api.Assertions.assertThat;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** Real PostgreSQL evidence for the new authoritative administration records. */
@Testcontainers
class PostgreSqlInventoryAdministrationSchemaTest {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("postgres:18.6-trixie@sha256:4ef4dbc939d61acea57712655ddb4b4ab27419c913f94cca0cd57cb3ea3c2280")
                    .asCompatibleSubstituteFor("postgres"));

    static JdbcTemplate jdbc;

    @BeforeAll
    static void migrate() {
        var source = new DriverManagerDataSource(POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(source).load().migrate();
        jdbc = new JdbcTemplate(source);
    }

    @Test
    void createsDurableMovementAuthority() {
        assertThat(jdbc.queryForObject("SELECT to_regclass('inventory.movements')::text", String.class))
                .isEqualTo("inventory.movements");
    }

    @Test
    void createsOwnerLocalPolicyEvidence() {
        assertThat(jdbc.queryForObject("SELECT to_regclass('inventory.policy_changes')::text", String.class))
                .isEqualTo("inventory.policy_changes");
    }
}
