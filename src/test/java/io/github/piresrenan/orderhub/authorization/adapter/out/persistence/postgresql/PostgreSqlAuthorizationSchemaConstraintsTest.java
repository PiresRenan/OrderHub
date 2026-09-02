package io.github.piresrenan.orderhub.authorization.adapter.out.persistence.postgresql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
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

import io.github.piresrenan.orderhub.authorization.domain.model.PermissionCode;

@Testcontainers
class PostgreSqlAuthorizationSchemaConstraintsTest {

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

    @Test
    void seedsExactlyTheExecutableSystemPermissionCatalog() {

        var persisted =
                jdbcTemplate.queryForList(
                                """
                                SELECT code
                                FROM access_control.permissions
                                ORDER BY code
                                """,
                                String.class)
                        .stream()
                        .sorted()
                        .toList();

        var expected =
                Arrays.stream(
                                PermissionCode.values())
                        .map(Enum::name)
                        .sorted()
                        .toList();

        assertThat(persisted)
                .containsExactlyElementsOf(
                        expected);
    }

    @Test
    void databaseRejectsDuplicateRoleAssignment() {

        var roleId =
                insertSystemRole(
                        "INVENTORY_OPERATOR");

        var userId =
                UUID.randomUUID();

        var tenantId =
                UUID.randomUUID();

        insertAssignment(
                UUID.randomUUID(),
                userId,
                tenantId,
                roleId);

        assertThatThrownBy(() ->
                insertAssignment(
                        UUID.randomUUID(),
                        userId,
                        tenantId,
                        roleId))
                .isInstanceOf(
                        DataIntegrityViolationException.class);
    }

    @Test
    void tenantCustomRoleRequiresTenantOwnership() {

        assertThatThrownBy(() ->
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
                        VALUES (?, NULL, ?, 'STAFF', 'OPERATIONAL', 'TENANT_CUSTOM')
                        """,
                        UUID.randomUUID(),
                        "CUSTOM_WAREHOUSE_OPERATOR"))
                .isInstanceOf(
                        DataIntegrityViolationException.class);
    }

    @Test
    void tenantCustomRoleCannotShadowSystemRoleCode() {

        var code =
                "SYSTEM_NAMESPACE_TEST_ROLE";

        insertSystemRole(
                code);

        assertThatThrownBy(() ->
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
                        VALUES (?, ?, ?, 'STAFF', 'OPERATIONAL', 'TENANT_CUSTOM')
                        """,
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        code))
                .isInstanceOf(
                        DataIntegrityViolationException.class);
    }

    @Test
    void tenantCustomRoleCodeMayBeReusedAcrossDifferentTenants() {

        var code =
                "SHARED_CUSTOM_WAREHOUSE_ROLE";

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
                VALUES (?, ?, ?, 'STAFF', 'OPERATIONAL', 'TENANT_CUSTOM')
                """,
                UUID.randomUUID(),
                UUID.randomUUID(),
                code);

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
                VALUES (?, ?, ?, 'STAFF', 'OPERATIONAL', 'TENANT_CUSTOM')
                """,
                UUID.randomUUID(),
                UUID.randomUUID(),
                code);

        assertThat(
                jdbcTemplate.queryForObject(
                        """
                        SELECT COUNT(*)
                        FROM access_control.role_definitions
                        WHERE code = ?
                          AND tenant_id IS NOT NULL
                        """,
                        Integer.class,
                        code))
                .isEqualTo(
                        2);
    }
    @Test
    void databaseRejectsTenantCustomRoleAssignmentOutsideOwningTenant() {

        var owningTenantId =
                UUID.randomUUID();

        var foreignTenantId =
                UUID.randomUUID();

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
                VALUES (?, ?, ?, 'STAFF', 'OPERATIONAL', 'TENANT_CUSTOM')
                """,
                roleId,
                owningTenantId,
                "CUSTOM_SCOPE_BOUND_ASSIGNMENT_ROLE");

        assertThatThrownBy(() ->
                insertAssignment(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        foreignTenantId,
                        roleId))
                .isInstanceOf(
                        DataIntegrityViolationException.class);
    }
    @Test
    void databaseRejectsTenantCustomRoleOwnershipTransferAfterAssignment() {

        var owningTenantId =
                UUID.randomUUID();

        var foreignTenantId =
                UUID.randomUUID();

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
                VALUES (?, ?, ?, 'STAFF', 'OPERATIONAL', 'TENANT_CUSTOM')
                """,
                roleId,
                owningTenantId,
                "CUSTOM_IMMUTABLE_SCOPE_ROLE");

        insertAssignment(
                UUID.randomUUID(),
                UUID.randomUUID(),
                owningTenantId,
                roleId);

        assertThatThrownBy(() ->
                jdbcTemplate.update(
                        """
                        UPDATE access_control.role_definitions
                        SET tenant_id = ?
                        WHERE role_id = ?
                        """,
                        foreignTenantId,
                        roleId))
                .isInstanceOf(
                        DataIntegrityViolationException.class);
    }
    @Test
    void databaseRejectsStableRoleCodeRewrite() {

        var roleId =
                insertSystemRole(
                        "IMMUTABLE_ROLE_CODE");

        assertThatThrownBy(() ->
                jdbcTemplate.update(
                        """
                        UPDATE access_control.role_definitions
                        SET code = 'REWRITTEN_ROLE_CODE'
                        WHERE role_id = ?
                        """,
                        roleId))
                .isInstanceOf(
                        DataIntegrityViolationException.class);
    }

    @Test
    void databaseRejectsRolePermissionPersonaMismatchAndPermissionPersonaRewrite() {

        var roleId =
                insertSystemRole(
                        "PERSONA_BOUND_ROLE");

        jdbcTemplate.update(
                """
                INSERT INTO access_control.permissions (
                    code,
                    persona
                )
                VALUES (
                    'CUSTOMER_RESOURCE_VIEW_TEST',
                    'CUSTOMER'
                )
                """);

        assertThatThrownBy(() ->
                jdbcTemplate.update(
                        """
                        INSERT INTO access_control.role_permissions (
                            role_id,
                            permission_code
                        )
                        VALUES (
                            ?,
                            'CUSTOMER_RESOURCE_VIEW_TEST'
                        )
                        """,
                        roleId))
                .isInstanceOf(
                        DataIntegrityViolationException.class);

        jdbcTemplate.update(
                """
                INSERT INTO access_control.permissions (
                    code,
                    persona
                )
                VALUES (
                    'STAFF_IMMUTABLE_PERSONA_TEST',
                    'STAFF'
                )
                """);

        jdbcTemplate.update(
                """
                INSERT INTO access_control.role_permissions (
                    role_id,
                    permission_code
                )
                VALUES (
                    ?,
                    'STAFF_IMMUTABLE_PERSONA_TEST'
                )
                """,
                roleId);

        assertThatThrownBy(() ->
                jdbcTemplate.update(
                        """
                        UPDATE access_control.permissions
                        SET persona = 'CUSTOMER'
                        WHERE code = 'STAFF_IMMUTABLE_PERSONA_TEST'
                        """))
                .isInstanceOf(
                        DataIntegrityViolationException.class);
    }
    @Test
    void authorizationForeignKeysNeverTargetUsersOrTenantsSchemas() {

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

    @AfterEach
    void cleanFinalCandidateSyntheticFixtures() {

        jdbcTemplate.update(
                """
                DELETE FROM access_control.role_permissions
                WHERE permission_code IN (
                    'CUSTOMER_RESOURCE_VIEW_TEST',
                    'STAFF_IMMUTABLE_PERSONA_TEST'
                )
                """);

        jdbcTemplate.update(
                """
                DELETE FROM access_control.permissions
                WHERE code IN (
                    'CUSTOMER_RESOURCE_VIEW_TEST',
                    'STAFF_IMMUTABLE_PERSONA_TEST'
                )
                """);

        jdbcTemplate.update(
                """
                DELETE FROM access_control.role_definitions
                WHERE code IN (
                    'IMMUTABLE_ROLE_CODE',
                    'PERSONA_BOUND_ROLE'
                )
                """);
    }
    private static UUID insertSystemRole(
            String code) {

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
                VALUES (?, NULL, ?, 'STAFF', 'OPERATIONAL', 'BUILTIN_FUNCTIONAL')
                """,
                roleId,
                code);

        return roleId;
    }

    private static void insertAssignment(
            UUID assignmentId,
            UUID userId,
            UUID tenantId,
            UUID roleId) {

        jdbcTemplate.update(
                """
                INSERT INTO access_control.role_assignments (
                    assignment_id,
                    user_id,
                    tenant_id,
                    persona,
                    role_id
                )
                VALUES (?, ?, ?, 'STAFF', ?)
                """,
                assignmentId,
                userId,
                tenantId,
                roleId);
    }
}
