package io.github.piresrenan.orderhub.organizations.adapter.out.persistence.postgresql;

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

@Testcontainers
class OrganizationTenantPlacementSchemaConstraintsTest {

    private static final DockerImageName POSTGRES_IMAGE =
            DockerImageName.parse(
                    "postgres:18.6-trixie@sha256:"
                            + "4ef4dbc939d61acea57712655ddb4b4ab27419c913f94cca0cd57cb3ea3c2280")
                    .asCompatibleSubstituteFor("postgres");

    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(POSTGRES_IMAGE)
                    .withDatabaseName("orderhub_test")
                    .withUsername("orderhub_test")
                    .withPassword("synthetic-test-password");

    private static JdbcTemplate jdbcTemplate;

    @BeforeAll
    static void migrateSchema() {

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
    }

    @BeforeEach
    void clearState() {

        jdbcTemplate.update(
                "TRUNCATE TABLE organizations.tenant_placements");

        jdbcTemplate.update(
                "TRUNCATE TABLE organizations.organizations CASCADE");
    }

    @Test
    void tenantIdPrimaryKeyStructurallyEnforcesSingleParent() {

        var tenantId =
                UUID.randomUUID();

        var organizationA =
                seedOrganization(
                        "Organization A");

        var organizationB =
                seedOrganization(
                        "Organization B");

        insertPlacement(
                tenantId,
                organizationA);

        assertThatThrownBy(() ->
                insertPlacement(
                        tenantId,
                        organizationB))
                .isInstanceOf(
                        DataIntegrityViolationException.class);
    }

    @Test
    void placementRequiresExistingOrganizationOwnedBySameModule() {

        assertThatThrownBy(() ->
                insertPlacement(
                        UUID.randomUUID(),
                        UUID.randomUUID()))
                .isInstanceOf(
                        DataIntegrityViolationException.class);
    }

    @Test
    void placementSchemaDoesNotCreateCrossModuleForeignKeyIntoTenants() {

        var foreignKeysIntoTenantSchema =
                jdbcTemplate.queryForObject(
                        """
                        SELECT count(*)
                        FROM pg_constraint constraint_row
                        JOIN pg_class source_table
                          ON source_table.oid = constraint_row.conrelid
                        JOIN pg_namespace source_schema
                          ON source_schema.oid = source_table.relnamespace
                        JOIN pg_class target_table
                          ON target_table.oid = constraint_row.confrelid
                        JOIN pg_namespace target_schema
                          ON target_schema.oid = target_table.relnamespace
                        WHERE constraint_row.contype = 'f'
                          AND source_schema.nspname = 'organizations'
                          AND source_table.relname = 'tenant_placements'
                          AND target_schema.nspname = 'tenants'
                        """,
                        Integer.class);

        assertThat(foreignKeysIntoTenantSchema)
                .isZero();
    }

    @Test
    void organizationLookupIndexExistsForFutureBoundedListing() {

        var indexCount =
                jdbcTemplate.queryForObject(
                        """
                        SELECT count(*)
                        FROM pg_indexes
                        WHERE schemaname = 'organizations'
                          AND tablename = 'tenant_placements'
                          AND indexname = 'idx_organization_tenant_placements_organization'
                        """,
                        Integer.class);

        assertThat(indexCount)
                .isEqualTo(
                        1);
    }

    private UUID seedOrganization(
            String name) {

        var organizationId =
                UUID.randomUUID();

        jdbcTemplate.update(
                """
                INSERT INTO organizations.organizations (
                    id,
                    name,
                    status
                )
                VALUES (?, ?, 'ACTIVE')
                """,
                organizationId,
                name);

        return organizationId;
    }

    private void insertPlacement(
            UUID tenantId,
            UUID organizationId) {

        jdbcTemplate.update(
                """
                INSERT INTO organizations.tenant_placements (
                    tenant_id,
                    organization_id
                )
                VALUES (?, ?)
                """,
                tenantId,
                organizationId);
    }
}
