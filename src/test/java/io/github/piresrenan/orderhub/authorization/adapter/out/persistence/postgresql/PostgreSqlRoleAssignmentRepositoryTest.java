package io.github.piresrenan.orderhub.authorization.adapter.out.persistence.postgresql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

import io.github.piresrenan.orderhub.authorization.adapter.out.persistence.postgresql.PostgreSqlRoleAssignmentRepository;
import io.github.piresrenan.orderhub.authorization.application.port.out.AuthorizationPersistenceException;
import io.github.piresrenan.orderhub.authorization.application.port.out.RoleAssignmentRepository;
import io.github.piresrenan.orderhub.authorization.domain.model.AuthorizationPersona;
import io.github.piresrenan.orderhub.authorization.domain.model.RoleAssignment;
import io.github.piresrenan.orderhub.authorization.domain.model.TenantAuthorizationScope;

@Testcontainers
class PostgreSqlRoleAssignmentRepositoryTest {

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

    private static RoleAssignmentRepository repository;

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
                new PostgreSqlRoleAssignmentRepository(
                        jdbcTemplate);
    }

    @BeforeEach
    void cleanAuthorizationState() {

        jdbcTemplate.update(
                """
                TRUNCATE TABLE
                    access_control.user_permission_overrides,
                    access_control.role_assignments,
                    access_control.role_permissions,
                    access_control.role_definitions
                CASCADE
                """);
    }

    @Test
    void savesAndFindsTenantScopedRoleAssignment() {

        insertRole(
                null,
                "INVENTORY_OPERATOR");

        var userId =
                UUID.randomUUID();

        var scope =
                scope();

        var assignment =
                assignment(
                        userId,
                        scope,
                        "INVENTORY_OPERATOR");

        repository.save(
                assignment);

        assertThat(
                repository.findByUserIdAndScope(
                        userId,
                        scope))
                .containsExactly(
                        assignment);
    }

    @Test
    void isolatesSameUserAssignmentsAcrossTenants() {

        insertRole(
                null,
                "INVENTORY_OPERATOR");

        insertRole(
                null,
                "ORDER_OPERATOR");

        var userId =
                UUID.randomUUID();

        var tenantA =
                scope();

        var tenantB =
                scope();

        repository.save(
                assignment(
                        userId,
                        tenantA,
                        "INVENTORY_OPERATOR"));

        repository.save(
                assignment(
                        userId,
                        tenantB,
                        "ORDER_OPERATOR"));

        assertThat(
                repository.findByUserIdAndScope(
                        userId,
                        tenantA))
                .extracting(
                        RoleAssignment::roleCode)
                .containsExactly(
                        "INVENTORY_OPERATOR");

        assertThat(
                repository.findByUserIdAndScope(
                        userId,
                        tenantB))
                .extracting(
                        RoleAssignment::roleCode)
                .containsExactly(
                        "ORDER_OPERATOR");
    }

    @Test
    void tenantCustomRoleCannotBeResolvedFromAnotherTenant() {

        var tenantA =
                scope();

        var tenantB =
                scope();

        insertRole(
                tenantA.tenantId(),
                "CUSTOM_WAREHOUSE_OPERATOR");

        assertThatThrownBy(() ->
                repository.save(
                        assignment(
                                UUID.randomUUID(),
                                tenantB,
                                "CUSTOM_WAREHOUSE_OPERATOR")))
                .isInstanceOf(
                        AuthorizationPersistenceException.class)
                .hasMessage(
                        "Role definition is unavailable in the requested Tenant scope");
    }

    @Test
    void savingSameAssignmentTwiceIsIdempotentAtRepositoryBoundary() {

        insertRole(
                null,
                "INVENTORY_OPERATOR");

        var userId =
                UUID.randomUUID();

        var scope =
                scope();

        var assignment =
                assignment(
                        userId,
                        scope,
                        "INVENTORY_OPERATOR");

        repository.save(
                assignment);

        repository.save(
                assignment);

        assertThat(
                repository.findByUserIdAndScope(
                        userId,
                        scope))
                .containsExactly(
                        assignment);

        assertThat(
                jdbcTemplate.queryForObject(
                        """
                        SELECT COUNT(*)
                        FROM access_control.role_assignments
                        WHERE user_id = ?
                          AND tenant_id = ?
                        """,
                        Integer.class,
                        userId,
                        scope.tenantId()))
                .isEqualTo(
                        1);
    }

    private static RoleAssignment assignment(
            UUID userId,
            TenantAuthorizationScope scope,
            String roleCode) {

        return new RoleAssignment(
                userId,
                AuthorizationPersona.STAFF,
                scope,
                roleCode);
    }

    private static TenantAuthorizationScope scope() {

        return new TenantAuthorizationScope(
                UUID.randomUUID());
    }

    private static void insertRole(
            UUID tenantId,
            String code) {

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
                VALUES (?, ?, ?, 'STAFF', 'OPERATIONAL', ?)
                """,
                UUID.randomUUID(),
                tenantId,
                code,
                tenantId == null
                        ? "BUILTIN_FUNCTIONAL"
                        : "TENANT_CUSTOM");
    }
}
