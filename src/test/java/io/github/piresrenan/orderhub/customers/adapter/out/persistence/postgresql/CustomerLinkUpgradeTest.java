package io.github.piresrenan.orderhub.customers.adapter.out.persistence.postgresql;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Why: Customer identity and ownership must remain separate from Staff and arbitrary selectors.
 * Covers: Customer schema, exact ownership and linking behavior exercised by this suite.
 * Prevents: Account takeover, cross-Tenant ownership and regressions hidden by invalid cleanup fixtures.
 */
@Testcontainers
class CustomerLinkUpgradeTest {
    @Container private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(DockerImageName.parse(
            "postgres:18.6-trixie@sha256:4ef4dbc939d61acea57712655ddb4b4ab27419c913f94cca0cd57cb3ea3c2280")
            .asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("orderhub_test").withUsername("orderhub_test").withPassword("synthetic-test-password");

    @Test void publishedCustomersAndMigrationChecksumsSurviveUpgrade() {
        var source = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(source).locations("classpath:db/migration").target("40").load().migrate();
        var jdbc = new JdbcTemplate(source);
        var tenant = UUID.randomUUID(); var customer = UUID.randomUUID(); var user = UUID.randomUUID();
        jdbc.update("INSERT INTO customers.customer_profiles VALUES (?, ?)", tenant, customer);
        jdbc.update("INSERT INTO customers.customer_account_bindings VALUES (?, ?, ?)", tenant, customer, user);
        var original = jdbc.queryForList("SELECT * FROM customers.customer_account_bindings");
        var checksums = jdbc.queryForList("SELECT version, checksum FROM flyway_schema_history WHERE version IS NOT NULL");
        Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();
        assertThat(jdbc.queryForList("SELECT * FROM customers.customer_account_bindings")).isEqualTo(original);
        assertThat(jdbc.queryForList("SELECT version, checksum FROM flyway_schema_history WHERE version IS NOT NULL")).containsAll(checksums);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM customers.account_link_proofs", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM customers.account_link_events", Integer.class)).isZero();
    }
}
