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

import io.github.piresrenan.orderhub.authorization.application.port.out.UserPermissionOverrideRepository;
import io.github.piresrenan.orderhub.authorization.domain.model.PermissionCode;
import io.github.piresrenan.orderhub.authorization.domain.model.PermissionEffect;
import io.github.piresrenan.orderhub.authorization.domain.model.TenantAuthorizationScope;

@Testcontainers
class PostgreSqlUserPermissionOverrideRepositoryTest {

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

    private static UserPermissionOverrideRepository repository;

    @BeforeAll
    static void migrateAndCreateRepository() {

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
                new PostgreSqlUserPermissionOverrideRepository(
                        jdbcTemplate);
    }

    @BeforeEach
    void cleanOverrides() {

        jdbcTemplate.update(
                """
                TRUNCATE TABLE
                    access_control.user_permission_overrides
                """);
    }

    @Test
    void loadsAllowAndDenyOverridesFromTheRequestedScope() {

        var userId =
                UUID.randomUUID();

        var scope =
                scope();

        insertOverride(
                userId,
                scope,
                PermissionCode.INVENTORY_ADJUST,
                PermissionEffect.ALLOW);

        insertOverride(
                userId,
                scope,
                PermissionCode.INVENTORY_POLICY_MANAGE,
                PermissionEffect.DENY);

        var overrides =
                repository.findByUserIdAndScope(
                        userId,
                        scope);

        assertThat(overrides)
                .hasSize(2);

        assertThat(overrides)
                .extracting(entry ->
                        entry.override()
                                .permission())
                .containsExactly(
                        PermissionCode.INVENTORY_ADJUST,
                        PermissionCode.INVENTORY_POLICY_MANAGE);

        assertThat(overrides)
                .extracting(entry ->
                        entry.override()
                                .effect())
                .containsExactly(
                        PermissionEffect.ALLOW,
                        PermissionEffect.DENY);
    }

    @Test
    void isolatesOverridesForTheSameUserAcrossTenants() {

        var userId =
                UUID.randomUUID();

        var tenantA =
                scope();

        var tenantB =
                scope();

        insertOverride(
                userId,
                tenantA,
                PermissionCode.INVENTORY_ADJUST,
                PermissionEffect.ALLOW);

        insertOverride(
                userId,
                tenantB,
                PermissionCode.INVENTORY_POLICY_MANAGE,
                PermissionEffect.DENY);

        assertThat(
                repository.findByUserIdAndScope(
                        userId,
                        tenantA))
                .extracting(entry ->
                        entry.override()
                                .permission())
                .containsExactly(
                        PermissionCode.INVENTORY_ADJUST);
    }

    @Test
    void isolatesOverridesForDifferentUsersInsideTheSameTenant() {

        var userA =
                UUID.randomUUID();

        var userB =
                UUID.randomUUID();

        var scope =
                scope();

        insertOverride(
                userA,
                scope,
                PermissionCode.INVENTORY_ADJUST,
                PermissionEffect.ALLOW);

        insertOverride(
                userB,
                scope,
                PermissionCode.INVENTORY_POLICY_MANAGE,
                PermissionEffect.DENY);

        assertThat(
                repository.findByUserIdAndScope(
                        userA,
                        scope))
                .extracting(entry ->
                        entry.override()
                                .permission())
                .containsExactly(
                        PermissionCode.INVENTORY_ADJUST);
    }

    private static void insertOverride(
            UUID userId,
            TenantAuthorizationScope scope,
            PermissionCode permission,
            PermissionEffect effect) {

        jdbcTemplate.update(
                """
                INSERT INTO access_control.user_permission_overrides (
                    override_id,
                    user_id,
                    tenant_id,
                    permission_code,
                    effect
                )
                VALUES (?, ?, ?, ?, ?)
                """,
                UUID.randomUUID(),
                userId,
                scope.tenantId(),
                permission.name(),
                effect.name());
    }

    private static TenantAuthorizationScope scope() {

        return new TenantAuthorizationScope(
                UUID.randomUUID());
    }
}
