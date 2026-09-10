package io.github.piresrenan.orderhub.users.adapter.out.persistence.postgresql;

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

@Testcontainers
class ExternalIdentityLifecycleUpgradeTest {
    @Container private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(DockerImageName.parse(
            "postgres:18.6-trixie@sha256:4ef4dbc939d61acea57712655ddb4b4ab27419c913f94cca0cd57cb3ea3c2280")
            .asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("orderhub_test").withUsername("orderhub_test").withPassword("synthetic-test-password");

    @Test void v41IdentityAndBusinessReferencesSurviveWithoutReassignment() {
        var source = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(source).locations("classpath:db/migration").target("41").load().migrate();
        var jdbc = new JdbcTemplate(source);
        var user = UUID.randomUUID(); var tenant = UUID.randomUUID(); var customer = UUID.randomUUID();
        jdbc.update("INSERT INTO users.users (id) VALUES (?)", user);
        jdbc.update("INSERT INTO users.external_identity_bindings (issuer, subject, user_id) VALUES ('https://Synthetic-Upgrade.test/Exact', ' Subject-With-Case ', ?)", user);
        jdbc.update("INSERT INTO users.tenant_memberships (user_id, tenant_id, status) VALUES (?, ?, 'SUSPENDED')", user, tenant);
        jdbc.update("INSERT INTO customers.customer_profiles VALUES (?, ?)", tenant, customer);
        jdbc.update("INSERT INTO customers.customer_account_bindings VALUES (?, ?, ?)", tenant, customer, user);
        var identities = jdbc.queryForList("SELECT issuer, subject, user_id FROM users.external_identity_bindings");
        var memberships = jdbc.queryForList("SELECT * FROM users.tenant_memberships");
        var customers = jdbc.queryForList("SELECT * FROM customers.customer_account_bindings");
        var checksums = jdbc.queryForList("SELECT version, checksum FROM flyway_schema_history WHERE version IS NOT NULL");
        Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();
        assertThat(jdbc.queryForList("SELECT issuer, subject, user_id FROM users.external_identity_bindings")).isEqualTo(identities);
        assertThat(jdbc.queryForList("SELECT * FROM users.tenant_memberships")).isEqualTo(memberships);
        assertThat(jdbc.queryForList("SELECT * FROM customers.customer_account_bindings")).isEqualTo(customers);
        assertThat(jdbc.queryForList("SELECT version, checksum FROM flyway_schema_history WHERE version IS NOT NULL")).containsAll(checksums);
        assertThat(jdbc.queryForObject("SELECT active FROM users.external_identity_bindings WHERE user_id = ?", Boolean.class, user)).isTrue();
        assertThat(jdbc.queryForObject("SELECT binding_id FROM users.external_identity_bindings WHERE user_id = ?", UUID.class, user)).isNotNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users.external_identity_events", Integer.class)).isZero();
    }
}
