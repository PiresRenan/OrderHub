package io.github.piresrenan.orderhub.users.adapter.out.persistence.postgresql;

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

import io.github.piresrenan.orderhub.users.application.port.out.TenantMembershipAlreadyExistsException;
import io.github.piresrenan.orderhub.users.application.port.out.TenantMembershipPersistenceException;
import io.github.piresrenan.orderhub.users.domain.model.TenantMembership;

@Testcontainers
class PostgreSqlTenantMembershipRepositoryTest {

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
        // Why: repository behavior must be verified against the accepted cumulative
        // Flyway history and real PostgreSQL constraints.
        // Covers: database construction through the current migration history.
        // Prevents: repository tests relying on an artificial schema.

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
        // Why: every repository scenario needs deterministic persisted state.
        // Covers: isolation of Users and TenantMembership rows between tests.
        // Prevents: previous Users or memberships changing later test outcomes.

        jdbcTemplate.update(
                "TRUNCATE TABLE users.tenant_memberships, users.users");
    }

    @Test
    void savesAndReconstructsMembership() {
        // Why: persistence must round-trip the complete association through the
        // domain reconstruction boundary.
        // Covers: save plus exact-pair lookup against PostgreSQL for an existing
        // internal User.
        // Prevents: SQL or row-mapping defects bypassing TenantMembership.rehydrate.

        var repository = new PostgreSqlTenantMembershipRepository(
                jdbcTemplate);

        var userId = UUID.randomUUID();
        var tenantId = UUID.randomUUID();

        persistUser(userId);

        var membership = TenantMembership.create(
                userId,
                tenantId);

        var saved = repository.save(membership);
        var reconstructed = repository.find(
                userId,
                tenantId);

        assertThat(saved)
                .isSameAs(membership);

        assertThat(reconstructed)
                .isPresent();

        assertThat(reconstructed.orElseThrow().userId())
                .isEqualTo(userId);

        assertThat(reconstructed.orElseThrow().tenantId())
                .isEqualTo(tenantId);
    }

    @Test
    void returnsEmptyWhenExactMembershipDoesNotExist() {
        // Why: absence of one association is a normal query result.
        // Covers: exact-pair lookup when no row exists.
        // Prevents: normal absence being represented as an infrastructure failure.

        var repository = new PostgreSqlTenantMembershipRepository(
                jdbcTemplate);

        var result = repository.find(
                UUID.randomUUID(),
                UUID.randomUUID());

        assertThat(result)
                .isEmpty();
    }

    @Test
    void lookupIsScopedToCompleteUserTenantPair() {
        // Why: membership identity consists of both User and Tenant identifiers.
        // Covers: both identifiers participating in the SELECT predicate.
        // Prevents: an implementation matching only user_id or only tenant_id.

        var repository = new PostgreSqlTenantMembershipRepository(
                jdbcTemplate);

        var userId = UUID.randomUUID();
        var storedTenantId = UUID.randomUUID();

        persistUser(userId);

        repository.save(
                TenantMembership.create(
                        userId,
                        storedTenantId));

        var result = repository.find(
                userId,
                UUID.randomUUID());

        assertThat(result)
                .isEmpty();
    }

    @Test
    void rejectsDuplicateMembershipDeterministically() {
        // Why: concurrent or repeated establishment of the same membership must
        // produce one stable application-level conflict.
        // Covers: translation of PostgreSQL pair uniqueness conflict for a valid
        // existing User.
        // Prevents: database-specific duplicate-key behavior leaking through the
        // repository contract.

        var repository = new PostgreSqlTenantMembershipRepository(
                jdbcTemplate);

        var userId = UUID.randomUUID();
        var tenantId = UUID.randomUUID();

        persistUser(userId);

        repository.save(
                TenantMembership.create(
                        userId,
                        tenantId));

        assertThatThrownBy(() ->
                repository.save(
                        TenantMembership.create(
                                userId,
                                tenantId)))
                .isInstanceOf(TenantMembershipAlreadyExistsException.class)
                .hasMessage("Tenant membership already exists.");
    }

    @Test
    void duplicateFailureMessageDoesNotLeakIdentityOrSql() {
        // Why: even deterministic conflicts must not expose linkable identifiers,
        // SQL text or PostgreSQL implementation details.
        // Covers: privacy-safe duplicate-membership error contract.
        // Prevents: User/Tenant UUID or persistence detail disclosure.

        var repository = new PostgreSqlTenantMembershipRepository(
                jdbcTemplate);

        var userId = UUID.randomUUID();
        var tenantId = UUID.randomUUID();

        persistUser(userId);

        repository.save(
                TenantMembership.create(
                        userId,
                        tenantId));

        assertThatThrownBy(() ->
                repository.save(
                        TenantMembership.create(
                                userId,
                                tenantId)))
                .isInstanceOfSatisfying(
                        TenantMembershipAlreadyExistsException.class,
                        exception -> {

                            assertThat(exception.getMessage())
                                    .isEqualTo(
                                            "Tenant membership already exists.")
                                    .doesNotContain(userId.toString())
                                    .doesNotContain(tenantId.toString())
                                    .doesNotContainIgnoringCase("insert")
                                    .doesNotContainIgnoringCase(
                                            "tenant_memberships")
                                    .doesNotContainIgnoringCase("postgres");
                        });
    }

    @Test
    void rejectsMembershipForNonExistingUserWithoutLeakingPersistenceDetails() {
        // Why: the adapter must preserve the database-level User existence
        // invariant without exposing relational implementation details.
        // Covers: translation of a membership INSERT rejected by the User foreign
        // key.
        // Prevents: arbitrary or stale User identifiers becoming durable
        // memberships or leaking through persistence errors.

        var repository = new PostgreSqlTenantMembershipRepository(
                jdbcTemplate);

        var unknownUserId = UUID.randomUUID();
        var tenantId = UUID.randomUUID();

        assertThatThrownBy(() ->
                repository.save(
                        TenantMembership.create(
                                unknownUserId,
                                tenantId)))
                .isInstanceOfSatisfying(
                        TenantMembershipPersistenceException.class,
                        exception -> {

                            assertThat(exception.getMessage())
                                    .isEqualTo(
                                            "Tenant membership persistence operation failed.")
                                    .doesNotContain(unknownUserId.toString())
                                    .doesNotContain(tenantId.toString())
                                    .doesNotContainIgnoringCase("insert")
                                    .doesNotContainIgnoringCase(
                                            "tenant_memberships")
                                    .doesNotContainIgnoringCase("foreign key")
                                    .doesNotContainIgnoringCase(
                                            "fk_tenant_memberships_user")
                                    .doesNotContainIgnoringCase("postgres");
                        });
    }

    @Test
    void translatesGenericWriteFailureWithoutLeakingPersistenceDetails() {
        // Why: non-duplicate persistence failures are infrastructure failures and
        // must remain distinguishable from membership conflicts.
        // Covers: generic failed INSERT translation.
        // Prevents: JDBC/PostgreSQL implementation details escaping the adapter.

        var repository = new PostgreSqlTenantMembershipRepository(
                jdbcTemplate);

        try {
            jdbcTemplate.execute("""
                    ALTER TABLE users.tenant_memberships
                    RENAME TO tenant_memberships_unavailable
                    """);

            assertThatThrownBy(() ->
                    repository.save(
                            TenantMembership.create(
                                    UUID.randomUUID(),
                                    UUID.randomUUID())))
                    .isInstanceOfSatisfying(
                            TenantMembershipPersistenceException.class,
                            exception -> {

                                assertThat(exception.getMessage())
                                        .isEqualTo(
                                                "Tenant membership persistence operation failed.")
                                        .doesNotContainIgnoringCase("insert")
                                        .doesNotContainIgnoringCase(
                                                "tenant_memberships")
                                        .doesNotContainIgnoringCase("postgres");
                            });
        } finally {
            jdbcTemplate.execute("""
                    ALTER TABLE IF EXISTS users.tenant_memberships_unavailable
                    RENAME TO tenant_memberships
                    """);
        }
    }

    @Test
    void translatesReadFailureWithoutLeakingPersistenceDetails() {
        // Why: membership reads require the same stable infrastructure boundary as
        // writes.
        // Covers: generic failed SELECT translation.
        // Prevents: SQL/vendor details escaping from failed membership lookups.

        var repository = new PostgreSqlTenantMembershipRepository(
                jdbcTemplate);

        try {
            jdbcTemplate.execute("""
                    ALTER TABLE users.tenant_memberships
                    RENAME TO tenant_memberships_unavailable
                    """);

            assertThatThrownBy(() ->
                    repository.find(
                            UUID.randomUUID(),
                            UUID.randomUUID()))
                    .isInstanceOfSatisfying(
                            TenantMembershipPersistenceException.class,
                            exception -> {

                                assertThat(exception.getMessage())
                                        .isEqualTo(
                                                "Tenant membership persistence operation failed.")
                                        .doesNotContainIgnoringCase("select")
                                        .doesNotContainIgnoringCase(
                                                "tenant_memberships")
                                        .doesNotContainIgnoringCase("postgres");
                            });
        } finally {
            jdbcTemplate.execute("""
                    ALTER TABLE IF EXISTS users.tenant_memberships_unavailable
                    RENAME TO tenant_memberships
                    """);
        }
    }

    /**
     * Persists one synthetic internal User required by membership persistence.
     *
     * @param userId synthetic internal User identifier used by the current test
     */
    private void persistUser(
            UUID userId) {

        jdbcTemplate.update(
                """
                INSERT INTO users.users (
                    id
                )
                VALUES (?)
                """,
                userId);
    }
}
