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
class TenantMembershipEvidenceUpgradeTest {
    @Container private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(DockerImageName.parse(
            "postgres:18.6-trixie@sha256:4ef4dbc939d61acea57712655ddb4b4ab27419c913f94cca0cd57cb3ea3c2280").asCompatibleSubstituteFor("postgres"));

    @Test void v42HistoricalLifecycleStateAndChecksumsSurvive() {
        var source = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(source).target("42").load().migrate();
        var jdbc = new JdbcTemplate(source);
        for (var state : new String[]{"ACTIVE", "SUSPENDED", "TERMINATED"}) {
            var user = UUID.randomUUID(); var tenant = UUID.randomUUID();
            jdbc.update("INSERT INTO users.users (id) VALUES (?)", user);
            jdbc.update("INSERT INTO users.tenant_memberships (user_id, tenant_id, status) VALUES (?, ?, ?)", user, tenant, state);
            jdbc.update("INSERT INTO users.external_identity_bindings (issuer, subject, user_id, active) VALUES ('https://synthetic-upgrade.test', ?, ?, FALSE)", user.toString(), user);
        }
        var memberships = jdbc.queryForList("SELECT * FROM users.tenant_memberships ORDER BY user_id");
        var bindings = jdbc.queryForList("SELECT * FROM users.external_identity_bindings ORDER BY user_id");
        var checksums = jdbc.queryForList("SELECT version, checksum FROM flyway_schema_history WHERE version IS NOT NULL");
        Flyway.configure().dataSource(source).load().migrate();
        assertThat(jdbc.queryForList("SELECT * FROM users.tenant_memberships ORDER BY user_id")).isEqualTo(memberships);
        assertThat(jdbc.queryForList("SELECT * FROM users.external_identity_bindings ORDER BY user_id")).isEqualTo(bindings);
        assertThat(jdbc.queryForList("SELECT version, checksum FROM flyway_schema_history WHERE version IS NOT NULL")).containsAll(checksums);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users.tenant_membership_events", Integer.class)).isZero();
    }
}
