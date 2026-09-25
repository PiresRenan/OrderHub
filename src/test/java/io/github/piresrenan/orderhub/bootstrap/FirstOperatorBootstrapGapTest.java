package io.github.piresrenan.orderhub.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Why: OH-024 (ADR-0022) adds durable state only because the accepted V1-V45 schema cannot express it.
 * Scenario: a fresh retained database migrated through the complete accepted history.
 * Covers: RED-1 (no bound Platform operator can exist without manual mutation), RED-2/RED-3 (no durable
 * global one-shot arbitration object), RED-4 (normal grant audit demands a pre-existing actor) and RED-5
 * (normal resolution of an unbound identity yields nothing).
 * Expected: the gaps stay true for the normal grant audit and unbound identities, and the bootstrap-owned
 * singleton state and evidence relations exist from V46 onwards.
 * Prevents: silently removing the one-shot state or treating grant audit as a place for invented actors.
 */
@Testcontainers
class FirstOperatorBootstrapGapTest {

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("postgres:18.6-trixie@sha256:"
                    + "4ef4dbc939d61acea57712655ddb4b4ab27419c913f94cca0cd57cb3ea3c2280")
                    .asCompatibleSubstituteFor("postgres"));

    private static JdbcTemplate jdbc;

    @BeforeAll
    static void migrate() {
        var source = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(source);
    }

    @Test
    void freshDatabaseHasNoUserBindingOrPlatformAuthority() {
        // RED-1 / RED-5: nothing but a governed mutation can create the first bound Platform operator.
        assertThat(count("users.users")).isZero();
        assertThat(count("users.external_identity_bindings")).isZero();
        assertThat(count("access_control.administrative_grants")).isZero();
    }

    @Test
    void normalGrantAuditCannotRecordAGrantWithoutAPreExistingActor() {
        // RED-4: attributing the first grant to "nobody" is rejected; any value would be an invented actor.
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO access_control.administrative_grant_audit_events (audit_event_id, actor_user_id,
                    target_user_id, scope_type, scope_id, permission_code, action_type, outcome, correlation_id,
                    before_granted, after_granted)
                VALUES (?, NULL, ?, 'PLATFORM', NULL, 'PLATFORM_TENANTS_MANAGE', 'GRANT_PERMISSION', 'APPLIED', ?,
                    false, true)
                """, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void durableGlobalOneShotStateAndEvidenceExist() {
        // RED-2 / RED-3: without a database-owned singleton there is nothing to arbitrate two replicas.
        assertThat(jdbc.queryForObject("SELECT to_regclass('bootstrap.first_operator_ceremony')::text", String.class))
                .isEqualTo("bootstrap.first_operator_ceremony");
        assertThat(jdbc.queryForObject(
                "SELECT to_regclass('bootstrap.first_operator_ceremony_events')::text", String.class))
                .isEqualTo("bootstrap.first_operator_ceremony_events");
        assertThat(jdbc.queryForObject("SELECT state FROM bootstrap.first_operator_ceremony", String.class))
                .isEqualTo("OPEN");
    }

    private static long count(String relation) {
        return jdbc.queryForObject("SELECT count(*) FROM " + relation, Long.class);
    }
}
