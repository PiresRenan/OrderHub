package io.github.piresrenan.orderhub.authorization.adapter.out.persistence.postgresql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class PostgreSqlAdministrativeAuthorizationSchemaConstraintsTest {

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
                new JdbcTemplate(
                        dataSource);
    }

    @AfterEach
    void cleanSyntheticRows() {

        jdbcTemplate.update(
                "DELETE FROM access_control.administrative_grants");

        jdbcTemplate.update(
                """
                DELETE FROM access_control.role_definitions
                WHERE code = 'ADMIN_PERMISSION_ROLE_TEST'
                """);
    }

    @Test
    void administrativePermissionCatalogHasExactScopeClassification() {

        var persisted =
                jdbcTemplate.queryForList(
                        """
                        SELECT
                            code || ':' || administrative_scope
                        FROM access_control.permissions
                        WHERE administrative_scope IS NOT NULL
                        """,
                        String.class);

        assertThat(persisted)
                .containsExactlyInAnyOrder(
                        "PLATFORM_ORGANIZATIONS_VIEW:PLATFORM",
                        "PLATFORM_ORGANIZATIONS_MANAGE:PLATFORM",
                        "PLATFORM_TENANTS_MANAGE:PLATFORM",
                        "PLATFORM_ORGANIZATION_GRANTS_MANAGE:PLATFORM",
                        "ORGANIZATION_TENANTS_VIEW:ORGANIZATION");
    }

    @Test
    void permissionCannotBeBothTenantPersonaAndAdministrative() {

        assertThatThrownBy(() ->
                jdbcTemplate.update(
                        """
                        INSERT INTO access_control.permissions (
                            code,
                            persona,
                            administrative_scope
                        )
                        VALUES (
                            'INVALID_DUAL_CLASS_PERMISSION',
                            'STAFF',
                            'PLATFORM'
                        )
                        """))
                .isInstanceOf(
                        DataIntegrityViolationException.class);
    }

    @Test
    void permissionCannotRemainWithoutAClassification() {

        assertThatThrownBy(() ->
                jdbcTemplate.update(
                        """
                        INSERT INTO access_control.permissions (
                            code,
                            persona,
                            administrative_scope
                        )
                        VALUES (
                            'INVALID_UNCLASSIFIED_PERMISSION',
                            NULL,
                            NULL
                        )
                        """))
                .isInstanceOf(
                        DataIntegrityViolationException.class);
    }

    @Test
    void platformGrantRejectsScopedResourceIdentity() {

        assertThatThrownBy(() ->
                insertGrant(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        "PLATFORM",
                        UUID.randomUUID(),
                        "PLATFORM_ORGANIZATIONS_VIEW"))
                .isInstanceOf(
                        DataIntegrityViolationException.class);
    }

    @Test
    void organizationGrantRequiresScopedResourceIdentity() {

        assertThatThrownBy(() ->
                insertGrant(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        "ORGANIZATION",
                        null,
                        "ORGANIZATION_TENANTS_VIEW"))
                .isInstanceOf(
                        DataIntegrityViolationException.class);
    }

    @Test
    void platformPermissionCannotBePersistedAtOrganizationScope() {

        assertThatThrownBy(() ->
                insertGrant(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        "ORGANIZATION",
                        UUID.randomUUID(),
                        "PLATFORM_ORGANIZATIONS_MANAGE"))
                .isInstanceOf(
                        DataIntegrityViolationException.class);
    }

    @Test
    void organizationPermissionCannotBePersistedAtPlatformScope() {

        assertThatThrownBy(() ->
                insertGrant(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        "PLATFORM",
                        null,
                        "ORGANIZATION_TENANTS_VIEW"))
                .isInstanceOf(
                        DataIntegrityViolationException.class);
    }

    @Test
    void tenantBusinessPermissionCannotBecomeAdministrativeGrant() {

        assertThatThrownBy(() ->
                insertGrant(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        "TENANT",
                        UUID.randomUUID(),
                        "ORDERS_VIEW"))
                .isInstanceOf(
                        DataIntegrityViolationException.class);
    }

    @Test
    void administrativePermissionCannotBecomeTenantPermissionOverride() {

        assertThatThrownBy(() ->
                jdbcTemplate.update(
                        """
                        INSERT INTO access_control.user_permission_overrides (
                            override_id,
                            user_id,
                            tenant_id,
                            permission_code,
                            effect
                        )
                        VALUES (?, ?, ?, 'PLATFORM_ORGANIZATIONS_VIEW', 'ALLOW')
                        """,
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        UUID.randomUUID()))
                .isInstanceOf(
                        DataIntegrityViolationException.class);
    }

    @Test
    void administrativePermissionCannotBecomeTenantRolePermission() {

        var roleId =
                UUID.randomUUID();

        jdbcTemplate.update(
                """
                INSERT INTO access_control.role_definitions (
                    role_id,
                    tenant_id,
                    code,
                    persona,
                    authority_band,
                    mutability
                )
                VALUES (
                    ?,
                    NULL,
                    'ADMIN_PERMISSION_ROLE_TEST',
                    'STAFF',
                    'OPERATIONAL',
                    'BUILTIN_FUNCTIONAL'
                )
                """,
                roleId);

        assertThatThrownBy(() ->
                jdbcTemplate.update(
                        """
                        INSERT INTO access_control.role_permissions (
                            role_id,
                            permission_code
                        )
                        VALUES (?, 'PLATFORM_ORGANIZATIONS_VIEW')
                        """,
                        roleId))
                .isInstanceOf(
                        DataIntegrityViolationException.class);
    }

    @Test
    void authorizationForeignKeysRemainInsideAccessControlSchema() {

        var referencedSchemas =
                jdbcTemplate.queryForList(
                        """
                        SELECT DISTINCT referenced_namespace.nspname
                        FROM pg_constraint constraint_definition
                        JOIN pg_class source_table
                          ON source_table.oid = constraint_definition.conrelid
                        JOIN pg_namespace source_namespace
                          ON source_namespace.oid = source_table.relnamespace
                        JOIN pg_class referenced_table
                          ON referenced_table.oid = constraint_definition.confrelid
                        JOIN pg_namespace referenced_namespace
                          ON referenced_namespace.oid = referenced_table.relnamespace
                        WHERE constraint_definition.contype = 'f'
                          AND source_namespace.nspname = 'access_control'
                        ORDER BY referenced_namespace.nspname
                        """,
                        String.class);

        assertThat(referencedSchemas)
                .containsOnly(
                        "access_control");
    }

    @Test
    void platformGrantNaturalIdentityTreatsNullScopeAsEqual() {

        var userId =
                UUID.randomUUID();

        insertGrant(
                UUID.randomUUID(),
                userId,
                "PLATFORM",
                null,
                "PLATFORM_ORGANIZATIONS_VIEW");

        assertThatThrownBy(() ->
                insertGrant(
                        UUID.randomUUID(),
                        userId,
                        "PLATFORM",
                        null,
                        "PLATFORM_ORGANIZATIONS_VIEW"))
                .isInstanceOf(
                        DataIntegrityViolationException.class);
    }

    private static void insertGrant(
            UUID grantId,
            UUID userId,
            String scopeType,
            UUID scopeId,
            String permissionCode) {

        jdbcTemplate.update(
                """
                INSERT INTO access_control.administrative_grants (
                    grant_id,
                    user_id,
                    scope_type,
                    scope_id,
                    permission_code
                )
                VALUES (?, ?, ?, ?, ?)
                """,
                grantId,
                userId,
                scopeType,
                scopeId,
                permissionCode);
    }
}
