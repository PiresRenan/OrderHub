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

import io.github.piresrenan.orderhub.users.application.port.out.UserPersistenceException;
import io.github.piresrenan.orderhub.users.domain.model.User;

@Testcontainers
class PostgreSqlUserRepositoryTest {

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
        // Why: repository behavior must be verified against the same cumulative
        // Flyway schema used by the application.
        // Covers: real PostgreSQL setup through V1, V2 and immutable V3.
        // Prevents: repository tests passing against an artificial schema.

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
    void clearUsers() {
        // Why: repository tests require deterministic durable state.
        // Covers: isolation of users.users between test cases.
        // Prevents: rows from previous tests influencing repository behavior.

        jdbcTemplate.update(
                "TRUNCATE TABLE users.tenant_memberships, users.users");
    }

    @Test
    void savesAndReconstructsUser() {
        // Why: the adapter must persist and reconstruct the complete User domain
        // state rather than exposing relational state to the application.
        // Covers: save plus findById round trip through real PostgreSQL.
        // Prevents: SQL/row-mapping defects and bypass of User.rehydrate(...).

        var repository = new PostgreSqlUserRepository(
                jdbcTemplate);

        var userId = UUID.randomUUID();
        var user = User.create(userId);

        var saved = repository.save(user);
        var reconstructed = repository.findById(userId);

        assertThat(saved)
                .isSameAs(user);

        assertThat(reconstructed)
                .isPresent();

        assertThat(reconstructed.orElseThrow().id())
                .isEqualTo(userId);
    }

    @Test
    void returnsEmptyWhenUserDoesNotExist() {
        // Why: absence is a normal lookup result and must not become an
        // infrastructure failure.
        // Covers: deterministic findById behavior for an unknown identity.
        // Prevents: callers being forced to interpret JDBC exceptions as absence.

        var repository = new PostgreSqlUserRepository(
                jdbcTemplate);

        var result = repository.findById(
                UUID.randomUUID());

        assertThat(result)
                .isEmpty();
    }

    @Test
    void translatesWriteFailure() {
        // Why: application boundaries must not expose Spring/JDBC/PostgreSQL
        // exception types.
        // Covers: failed INSERT translation.
        // Prevents: infrastructure exception coupling escaping the adapter.

        var repository = new PostgreSqlUserRepository(
                jdbcTemplate);

        var userId = UUID.randomUUID();

        repository.save(
                User.create(userId));

        assertThatThrownBy(() ->
                repository.save(
                        User.create(userId)))
                .isInstanceOf(UserPersistenceException.class)
                .hasMessage("User persistence operation failed.");
    }

    @Test
    void writeFailureMessageDoesNotLeakPersistenceDetails() {
        // Why: persistence failures may contain SQL, identifiers and vendor-specific
        // details that must not become stable application-facing messages.
        // Covers: privacy-safe public exception contract for failed writes.
        // Prevents: accidental disclosure of SQL or User identity.

        var repository = new PostgreSqlUserRepository(
                jdbcTemplate);

        var userId = UUID.randomUUID();

        repository.save(
                User.create(userId));

        assertThatThrownBy(() ->
                repository.save(
                        User.create(userId)))
                .isInstanceOfSatisfying(
                        UserPersistenceException.class,
                        exception -> {

                            assertThat(exception.getMessage())
                                    .isEqualTo(
                                            "User persistence operation failed.")
                                    .doesNotContain(userId.toString())
                                    .doesNotContainIgnoringCase("insert")
                                    .doesNotContainIgnoringCase("users.users")
                                    .doesNotContainIgnoringCase("postgres");
                        });
    }

    @Test
    void translatesReadFailureWithoutLeakingPersistenceDetails() {
        // Why: read-side infrastructure failures require the same stable boundary as
        // write failures.
        // Covers: failed SELECT translation and safe public message.
        // Prevents: SQL/vendor details escaping when durable storage is unavailable
        // or structurally inaccessible.

        var repository = new PostgreSqlUserRepository(
                jdbcTemplate);

        try {
            jdbcTemplate.execute(
                    "ALTER TABLE users.users RENAME TO users_unavailable");

            assertThatThrownBy(() ->
                    repository.findById(
                            UUID.randomUUID()))
                    .isInstanceOfSatisfying(
                            UserPersistenceException.class,
                            exception -> {

                                assertThat(exception.getMessage())
                                        .isEqualTo(
                                                "User persistence operation failed.")
                                        .doesNotContainIgnoringCase("select")
                                        .doesNotContainIgnoringCase("users.users")
                                        .doesNotContainIgnoringCase("postgres");
                            });
        } finally {
            jdbcTemplate.execute("""
                    ALTER TABLE IF EXISTS users.users_unavailable
                    RENAME TO users
                    """);
        }
    }
}
