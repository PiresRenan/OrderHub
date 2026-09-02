package io.github.piresrenan.orderhub.authorization.adapter.out.persistence.postgresql;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import io.github.piresrenan.orderhub.authorization.application.port.out.RoleDefinitionRepository;
import io.github.piresrenan.orderhub.authorization.domain.model.AuthorityBand;
import io.github.piresrenan.orderhub.authorization.domain.model.AuthorizationPersona;
import io.github.piresrenan.orderhub.authorization.domain.model.PermissionCode;
import io.github.piresrenan.orderhub.authorization.domain.model.RoleMutability;
import io.github.piresrenan.orderhub.authorization.domain.model.TenantAuthorizationScope;

@Testcontainers
class PostgreSqlRoleDefinitionRepositoryTest {

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

    private static RoleDefinitionRepository repository;

    @BeforeAll
    static void migrateSchemaAndCreateRepository() {

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

        repository =
                new PostgreSqlRoleDefinitionRepository(
                        jdbcTemplate);
    }

    @BeforeEach
    void cleanRoleState() {

        jdbcTemplate.update(
                """
                TRUNCATE TABLE
                    access_control.role_assignments,
                    access_control.role_permissions,
                    access_control.role_definitions
                CASCADE
                """);
    }

    @Test
    void loadsRoleDefinitionWithPersistedPermissionMembership() {

        var roleId =
                insertSystemRole(
                        "INVENTORY_MANAGER",
                        "MANAGEMENT",
                        "BUILTIN_FUNCTIONAL");

        insertPermission(
                roleId,
                PermissionCode.INVENTORY_VIEW);

        insertPermission(
                roleId,
                PermissionCode.INVENTORY_POLICY_MANAGE);

        var persisted =
                repository.findByCodeAndScope(
                                "INVENTORY_MANAGER",
                                new TenantAuthorizationScope(
                                        UUID.randomUUID()))
                        .orElseThrow(() ->
                                new AssertionError(
                                        "Expected durable RoleDefinition to be loaded"));

        assertThat(persisted.code())
                .isEqualTo(
                        "INVENTORY_MANAGER");

        assertThat(persisted.persona())
                .isEqualTo(
                        AuthorizationPersona.STAFF);

        assertThat(persisted.authorityBand())
                .isEqualTo(
                        AuthorityBand.MANAGEMENT);

        assertThat(persisted.mutability())
                .isEqualTo(
                        RoleMutability.BUILTIN_FUNCTIONAL);

        assertThat(persisted.permissions())
                .containsExactlyInAnyOrder(
                        PermissionCode.INVENTORY_VIEW,
                        PermissionCode.INVENTORY_POLICY_MANAGE);

        assertThat(
                persisted.permissionEnvelope()
                        .permissions())
                .containsExactlyInAnyOrder(
                        PermissionCode.INVENTORY_VIEW,
                        PermissionCode.INVENTORY_POLICY_MANAGE);
    }

    private static UUID insertSystemRole(
            String code,
            String authorityBand,
            String mutability) {

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
                VALUES (?, NULL, ?, 'STAFF', ?, ?)
                """,
                roleId,
                code,
                authorityBand,
                mutability);

        return roleId;
    }

    private static void insertPermission(
            UUID roleId,
            PermissionCode permission) {

        jdbcTemplate.update(
                """
                INSERT INTO access_control.role_permissions (
                    role_id,
                    permission_code
                )
                VALUES (?, ?)
                """,
                roleId,
                permission.name());
    }
}
