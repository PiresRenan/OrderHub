package io.github.piresrenan.orderhub.inventory.adapter.out.persistence.postgresql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    /** Why: critical movement invariants must survive bypass of application validation.
     * Covers: actual INSERTs with invalid type, sign, range and forensic reason.
     * Prevents: another process persisting semantically invalid stock evidence. */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
        "RECEIPT,0,RECEIPT", "RECEIPT,-1,RECEIPT", "ADJUSTMENT,0,COUNT",
        "ADJUSTMENT,-9223372036854775808,COUNT", "TRANSFER,1,COUNT", "RECEIPT,1,free text"
    })
    void rejectsInvalidDurableMovementFacts(String type,long delta,String reason) {
        var tenant=java.util.UUID.randomUUID();
        assertThatThrownBy(()->jdbc.update("""
                INSERT INTO inventory.movements(tenant_id,operation_id,actor_user_id,variant_id,movement_type,delta,reason,correlation_id,occurred_at,fingerprint_version,fingerprint)
                VALUES (?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP,1,?)
                """,tenant,java.util.UUID.randomUUID(),java.util.UUID.randomUUID(),java.util.UUID.randomUUID(),type,delta,reason,java.util.UUID.randomUUID(),"0".repeat(64)))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory.movements WHERE tenant_id=?",Integer.class,tenant)).isZero();
    }

    /** Why: SQL UNKNOWN must not admit missing policy destinations or safety values.
     * Covers: null financial/availability destinations through direct persistence.
     * Prevents: append-only evidence that cannot reconstruct its material transition. */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"OVERSELL_POLICY","SAFETY_STOCK","UNKNOWN"})
    void rejectsMissingPolicyEvidenceDestination(String action) {
        assertThatThrownBy(()->jdbc.update("""
                INSERT INTO inventory.policy_changes(tenant_id,change_id,actor_user_id,action,reason,correlation_id,occurred_at)
                VALUES (?,?,?,?,'CHANGE',?,CURRENT_TIMESTAMP)
                """,java.util.UUID.randomUUID(),java.util.UUID.randomUUID(),java.util.UUID.randomUUID(),action,java.util.UUID.randomUUID()))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
}
