package io.github.piresrenan.orderhub.users.adapter.out.persistence.postgresql;

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
class UserSchemaConstraintsTest {

    private static final DockerImageName POSTGRES_IMAGE = DockerImageName.parse(
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
    static void migrateDatabase() {
        // Why: schema constraints must be verified against the exact cumulative
        // Flyway history used by production.
        // Covers: creation of a clean PostgreSQL database through V1, V2 and V3.
        // Prevents: constraint tests silently depending on manually prepared schema.

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

    @BeforeEach
    void clearUsersSchema() {
        // Why: each persistence-invariant test needs deterministic isolated state.
        // Covers: cleanup of data owned by the Users module.
        // Prevents: one test's rows influencing another test's constraint result.

        jdbcTemplate.update(
                "TRUNCATE TABLE users.tenant_memberships, users.users");
    }

    @Test
    void rejectsUserWithoutId() {
        // Why: database constraints must defend User identity even when application
        // validation is bypassed.
        // Covers: NOT NULL / primary-key protection for users.users.id.
        // Prevents: identity-less User rows entering durable state.

        assertThatThrownBy(() ->
                jdbcTemplate.update(
                        "INSERT INTO users.users (id) VALUES (?)",
                        new Object[] { null }))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsDuplicateUserId() {
        // Why: one internal User identity must represent at most one durable User.
        // Covers: primary-key uniqueness for users.users.id.
        // Prevents: duplicate rows representing the same internal identity.

        var userId = UUID.randomUUID();

        jdbcTemplate.update(
                "INSERT INTO users.users (id) VALUES (?)",
                userId);

        assertThatThrownBy(() ->
                jdbcTemplate.update(
                        "INSERT INTO users.users (id) VALUES (?)",
                        userId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsMembershipWithoutUserId() {
        // Why: membership cannot represent an association without User identity.
        // Covers: NOT NULL protection for tenant_memberships.user_id.
        // Prevents: structurally incomplete membership rows.

        assertThatThrownBy(() ->
                jdbcTemplate.update("""
                        INSERT INTO users.tenant_memberships (
                            user_id,
                            tenant_id
                        )
                        VALUES (?, ?)
                        """,
                        null,
                        UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsMembershipWithoutTenantId() {
        // Why: membership cannot represent an association without Tenant identity.
        // Covers: NOT NULL protection for tenant_memberships.tenant_id.
        // Prevents: structurally incomplete membership rows.

        assertThatThrownBy(() ->
                jdbcTemplate.update("""
                        INSERT INTO users.tenant_memberships (
                            user_id,
                            tenant_id
                        )
                        VALUES (?, ?)
                        """,
                        UUID.randomUUID(),
                        null))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsDuplicateUserTenantMembership() {
        // Why: membership is a set-like association; the same User/Tenant pair must
        // not be persisted more than once.
        // Covers: durable uniqueness of (tenant_id, user_id).
        // Prevents: duplicate memberships producing ambiguous counts and future
        // authorization inconsistencies.

        var userId = UUID.randomUUID();
        var tenantId = UUID.randomUUID();

        jdbcTemplate.update("""
                INSERT INTO users.tenant_memberships (
                    user_id,
                    tenant_id
                )
                VALUES (?, ?)
                """,
                userId,
                tenantId);

        assertThatThrownBy(() ->
                jdbcTemplate.update("""
                        INSERT INTO users.tenant_memberships (
                            user_id,
                            tenant_id
                        )
                        VALUES (?, ?)
                        """,
                        userId,
                        tenantId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void allowsSameUserInDifferentTenants() {
        // Why: one User may legitimately belong to multiple Tenants.
        // Covers: uniqueness being scoped to the complete User/Tenant pair.
        // Prevents: accidentally constraining user_id globally in memberships.

        var userId = UUID.randomUUID();

        jdbcTemplate.update("""
                INSERT INTO users.tenant_memberships (
                    user_id,
                    tenant_id
                )
                VALUES (?, ?)
                """,
                userId,
                UUID.randomUUID());

        jdbcTemplate.update("""
                INSERT INTO users.tenant_memberships (
                    user_id,
                    tenant_id
                )
                VALUES (?, ?)
                """,
                userId,
                UUID.randomUUID());

        var count = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM users.tenant_memberships
                WHERE user_id = ?
                """,
                Integer.class,
                userId);

        assertThat(count)
                .isEqualTo(2);
    }

    @Test
    void allowsDifferentUsersInSameTenant() {
        // Why: one Tenant must support membership for multiple Users.
        // Covers: uniqueness being scoped to the complete User/Tenant pair.
        // Prevents: accidentally constraining tenant_id globally.

        var tenantId = UUID.randomUUID();

        jdbcTemplate.update("""
                INSERT INTO users.tenant_memberships (
                    user_id,
                    tenant_id
                )
                VALUES (?, ?)
                """,
                UUID.randomUUID(),
                tenantId);

        jdbcTemplate.update("""
                INSERT INTO users.tenant_memberships (
                    user_id,
                    tenant_id
                )
                VALUES (?, ?)
                """,
                UUID.randomUUID(),
                tenantId);

        var count = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM users.tenant_memberships
                WHERE tenant_id = ?
                """,
                Integer.class,
                tenantId);

        assertThat(count)
                .isEqualTo(2);
    }

    @Test
    void hasNoCrossModuleForeignKeyToTenantsSchema() {
        // Why: Users may reference Tenant identity but must not acquire database-level
        // ownership coupling to the Tenants module.
        // Covers: absence of foreign keys from users.tenant_memberships to the
        // tenants schema.
        // Prevents: an accidental cross-module relational dependency bypassing
        // application/module boundaries.

        var crossModuleForeignKeys = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM information_schema.table_constraints tc
                JOIN information_schema.constraint_column_usage ccu
                  ON tc.constraint_name = ccu.constraint_name
                 AND tc.constraint_schema = ccu.constraint_schema
                WHERE tc.constraint_type = 'FOREIGN KEY'
                  AND tc.table_schema = 'users'
                  AND tc.table_name = 'tenant_memberships'
                  AND ccu.table_schema = 'tenants'
                """,
                Integer.class);

        assertThat(crossModuleForeignKeys)
                .isZero();
    }
}
