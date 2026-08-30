package io.github.piresrenan.orderhub.tenants.adapter.out.persistence.postgresql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Verifies relational integrity guarantees owned by the Tenants PostgreSQL
 * schema independently of application and repository behavior.
 */
@Testcontainers
class TenantSchemaConstraintsTest {

    private static final DockerImageName POSTGRES_IMAGE = DockerImageName.parse(
            "postgres:18.6-trixie@sha256:"
                    + "4ef4dbc939d61acea57712655ddb4b4ab27419c913f94cca0cd57cb3ea3c2280")
            .asCompatibleSubstituteFor("postgres");

    private static final UUID TENANT_ID = UUID.fromString(
            "11111111-1111-1111-1111-111111111111");

    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(POSTGRES_IMAGE)
                    .withDatabaseName("orderhub_test")
                    .withUsername("orderhub_test")
                    .withPassword("synthetic-test-password");

    private static JdbcTemplate jdbcTemplate;

    /**
     * Creates the schema exclusively through the production Flyway migration
     * history before relational constraint tests execute.
     */
    @BeforeAll
    static void migrateSchema() {
        var dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword());

        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();

        jdbcTemplate = new JdbcTemplate(dataSource);
    }

    /**
     * Removes Tenant rows before each scenario while preserving schema and Flyway
     * history.
     */
    @BeforeEach
    void cleanBusinessData() {
        jdbcTemplate.update("""
                TRUNCATE TABLE tenants.tenants
                """);
    }

    @Test
    void acceptsValidTenant() {
        // Why: defensive constraints must not reject legitimate canonical state.
        // Covers: normal Tenant insertion through the relational contract.
        // Prevents: over-constraining storage while protecting invalid states.

        insertTenant(
                TENANT_ID,
                "Acme Commerce");

        var count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM tenants.tenants
                WHERE id = ?
                """,
                Integer.class,
                TENANT_ID);

        assertThat(count)
                .isEqualTo(1);
    }

    @Test
    void rejectsMissingTenantId() {
        // Why: persisted aggregates require stable identity even when application
        // validation is bypassed.
        // Covers: relational non-null/primary-key identity invariant.
        // Prevents: identity-less Tenant rows entering durable state.

        assertThatThrownBy(() ->
                insertTenant(
                        null,
                        "Acme Commerce"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsDuplicateTenantId() {
        // Why: one Tenant identifier must resolve to exactly one aggregate.
        // Covers: Tenant primary-key uniqueness.
        // Prevents: ambiguous reconstruction of an aggregate identity.

        insertTenant(
                TENANT_ID,
                "Acme Commerce");

        assertThatThrownBy(() ->
                insertTenant(
                        TENANT_ID,
                        "Another Tenant"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsMissingTenantName() {
        // Why: durable state must preserve the domain requirement for a Tenant name.
        // Covers: name NOT NULL.
        // Prevents: persistence paths bypassing the mandatory-name invariant.

        assertThatThrownBy(() ->
                insertTenant(
                        TENANT_ID,
                        null))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsEmptyTenantName() {
        // Why: NOT NULL alone still permits an empty descriptive value.
        // Covers: minimum meaningful Tenant name constraint.
        // Prevents: direct SQL from storing state the domain cannot construct.

        assertThatThrownBy(() ->
                insertTenant(
                        TENANT_ID,
                        ""))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsWhitespaceOnlyTenantName() {
        // Why: whitespace is not meaningful Tenant identity metadata.
        // Covers: blank-name defense at the relational boundary.
        // Prevents: database state that becomes invalid once reconstructed by the
        // domain.

        assertThatThrownBy(() ->
                insertTenant(
                        TENANT_ID,
                        "   "))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsNonNormalizedTenantName() {
        // Why: persisted state must already use the canonical representation expected
        // by Tenant.rehydrate(...).
        // Covers: surrounding-space normalization invariant in storage.
        // Prevents: repository reads failing because durable state contains values the
        // domain would have normalized during creation.

        assertThatThrownBy(() ->
                insertTenant(
                        TENANT_ID,
                        "  Acme Commerce  "))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void acceptsTenantNameAtMaximumLength() {
        // Why: the database boundary must agree with the inclusive domain limit.
        // Covers: exact 120-character relational boundary.
        // Prevents: off-by-one disagreement between Java and PostgreSQL.

        var name = "a".repeat(120);

        insertTenant(
                TENANT_ID,
                name);

        var storedName = jdbcTemplate.queryForObject("""
                SELECT name
                FROM tenants.tenants
                WHERE id = ?
                """,
                String.class,
                TENANT_ID);

        assertThat(storedName)
                .isEqualTo(name);
    }

    @Test
    void rejectsTenantNameAboveMaximumLength() {
        // Why: storage must remain bounded even when callers bypass the domain.
        // Covers: the 120-character upper relational boundary.
        // Prevents: durable values that Tenant.rehydrate(...) would reject.

        assertThatThrownBy(() ->
                insertTenant(
                        TENANT_ID,
                        "a".repeat(121)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void measuresTenantNameLimitInCharactersRatherThanUtf8Bytes() {
        // Why: the domain counts Unicode code points rather than UTF-8 bytes or
        // UTF-16 code units.
        // Covers: PostgreSQL character-length semantics for non-ASCII input.
        // Prevents: international Tenant names receiving a smaller effective database
        // limit than equivalent ASCII names.

        var name = "😀".repeat(120);

        insertTenant(
                TENANT_ID,
                name);

        var storedLength = jdbcTemplate.queryForObject("""
                SELECT char_length(name)
                FROM tenants.tenants
                WHERE id = ?
                """,
                Integer.class,
                TENANT_ID);

        assertThat(storedLength)
                .isEqualTo(120);
    }

    /**
     * Inserts one Tenant directly through JDBC so relational tests cannot be
     * satisfied accidentally by domain or repository validation.
     *
     * @param tenantId aggregate identifier, including null for negative tests
     * @param name persisted Tenant name, including invalid values for negative tests
     */
    private void insertTenant(
            UUID tenantId,
            String name) {

        jdbcTemplate.update("""
                INSERT INTO tenants.tenants (
                    id,
                    name
                )
                VALUES (?, ?)
                """,
                tenantId,
                name);
    }
}
