package io.github.piresrenan.orderhub.tenants.adapter.out.persistence.postgresql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import io.github.piresrenan.orderhub.tenants.application.port.out.TenantPersistenceException;
import io.github.piresrenan.orderhub.tenants.domain.model.Tenant;

@Testcontainers
class PostgreSqlTenantRepositoryTest {

    private static final DockerImageName POSTGRES_IMAGE = DockerImageName.parse(
            "postgres:18.6-trixie@sha256:"
                    + "4ef4dbc939d61acea57712655ddb4b4ab27419c913f94cca0cd57cb3ea3c2280")
            .asCompatibleSubstituteFor("postgres");

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(POSTGRES_IMAGE)
            .withDatabaseName("orderhub_test")
            .withUsername("orderhub_test")
            .withPassword("synthetic-test-password");

    private static JdbcTemplate jdbcTemplate;

    private PostgreSqlTenantRepository repository;

    /**
     * Migrates the same PostgreSQL schema used by runtime persistence before
     * repository integration scenarios execute.
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
     * Isolates repository scenarios while preserving schema and Flyway history.
     */
    @BeforeEach
    void prepareRepository() {
        jdbcTemplate.update("""
                TRUNCATE TABLE tenants.tenants
                """);

        repository = new PostgreSqlTenantRepository(
                jdbcTemplate);
    }

    @Test
    void savesAndReconstructsTenant() {
        // Why: the persistence adapter must preserve aggregate identity and canonical
        // domain state through an actual PostgreSQL round trip.
        // Covers: INSERT mapping, tenant lookup and Tenant.rehydrate(...).
        // Prevents: repository mappings returning state different from what the
        // domain accepted.

        var id = UUID.randomUUID();

        var tenant = Tenant.create(
                id,
                "  Acme Commerce  ");

        var saved = repository.save(tenant);

        var loaded = repository.findById(id);

        assertThat(saved)
                .isSameAs(tenant);

        assertThat(loaded)
                .isPresent();

        assertThat(loaded.orElseThrow().id())
                .isEqualTo(id);

        assertThat(loaded.orElseThrow().name())
                .isEqualTo("Acme Commerce");
    }

    @Test
    void returnsEmptyWhenTenantDoesNotExist() {
        // Why: absence is a normal lookup result and not a technical persistence
        // failure.
        // Covers: repository lookup semantics for an unknown aggregate identity.
        // Prevents: missing Tenants being represented as infrastructure exceptions.

        var result = repository.findById(
                UUID.randomUUID());

        assertThat(result)
                .isEmpty();
    }

    @Test
    void translatesPersistenceFailureAtApplicationBoundary() {
        // Why: Spring JDBC and PostgreSQL exception types must not become the
        // repository contract exposed to application code.
        // Covers: DataAccessException translation during Tenant save.
        // Prevents: infrastructure-specific failures leaking through the output port.

        var id = UUID.randomUUID();

        repository.save(
                Tenant.create(
                        id,
                        "Acme Commerce"));

        assertThatThrownBy(() -> repository.save(
                Tenant.create(
                        id,
                        "Another Tenant")))
                .isInstanceOf(TenantPersistenceException.class)
                .hasMessage("Tenant persistence operation failed.")
                .hasCauseInstanceOf(DataAccessException.class);
    }

    @Test
    void persistenceFailureMessageDoesNotExposeTenantState() {
        // Why: technical failures can eventually reach logs or generic error
        // handling and must have a stable privacy-safe public message.
        // Covers: output-boundary exception sanitization.
        // Prevents: Tenant identifiers, names, SQL or vendor details being embedded in
        // the exception message exposed by the application boundary.

        var id = UUID.fromString(
                "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

        var name = "Synthetic Private Tenant";

        repository.save(
                Tenant.create(
                        id,
                        "Existing Tenant"));

        assertThatThrownBy(() -> repository.save(
                Tenant.create(
                        id,
                        name)))
                .isInstanceOf(TenantPersistenceException.class)
                .satisfies(exception -> {
                    assertThat(exception.getMessage())
                            .isEqualTo("Tenant persistence operation failed.")
                            .doesNotContain(
                                    id.toString(),
                                    name,
                                    "INSERT",
                                    "JDBC",
                                    "PostgreSQL",
                                    "PSQLException");
                });
    }

    @Test
    void translatesLookupFailureAtApplicationBoundary() {
        // Why: reads can fail independently of writes because of unavailable or
        // structurally inaccessible persistence resources.
        // Covers: DataAccessException translation during Tenant lookup.
        // Prevents: Spring JDBC or PostgreSQL exceptions escaping through the
        // TenantRepository output port.

        var tenantId = UUID.fromString(
                "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

        jdbcTemplate.execute("""
                ALTER TABLE tenants.tenants
                RENAME TO tenants_temporarily_unavailable
                """);

        try {
            assertThatThrownBy(() -> repository.findById(tenantId))
                    .isInstanceOf(TenantPersistenceException.class)
                    .hasMessage("Tenant persistence operation failed.")
                    .hasCauseInstanceOf(DataAccessException.class)
                    .satisfies(exception -> assertThat(exception.getMessage())
                            .doesNotContain(
                                    tenantId.toString(),
                                    "SELECT",
                                    "JDBC",
                                    "PostgreSQL",
                                    "PSQLException"));
        } finally {
            jdbcTemplate.execute("""
                    ALTER TABLE tenants.tenants_temporarily_unavailable
                    RENAME TO tenants
                    """);
        }
    }
}
