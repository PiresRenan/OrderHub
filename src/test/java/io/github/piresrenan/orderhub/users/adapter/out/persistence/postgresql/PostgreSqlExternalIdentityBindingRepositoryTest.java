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

import io.github.piresrenan.orderhub.users.domain.model.ExternalIdentityBinding;
import io.github.piresrenan.orderhub.users.application.port.out.ExternalIdentityBindingAlreadyExistsException;
import io.github.piresrenan.orderhub.users.application.port.out.ExternalIdentityBindingPersistenceException;

@Testcontainers
class PostgreSqlExternalIdentityBindingRepositoryTest {

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

    @BeforeAll
    static void migrateDatabase() {
        // Why: repository behavior must be tested against the accepted cumulative
        // Flyway schema rather than an artificial test-only table definition.
        // Covers: database reconstruction through immutable V1-V5.
        // Prevents: persistence tests passing against schema different from
        // production.

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
        // Why: every repository scenario needs deterministic isolated persisted
        // state.
        // Covers: cleanup respecting Users-owned foreign-key dependencies.
        // Prevents: one external identity or User influencing another test.

        jdbcTemplate.update("""
                TRUNCATE TABLE
                    users.external_identity_bindings,
                    users.tenant_memberships,
                    users.users
                """);
    }

    @Test
    void savesAndReconstructsExternalIdentityBinding() {
        // Why: the adapter must persist and reconstruct the complete association
        // between provider identity and internal User.
        // Covers: INSERT plus exact issuer/subject lookup through PostgreSQL.
        // Prevents: SQL or row-mapping defects breaking authentication identity
        // resolution.

        var repository = new PostgreSqlExternalIdentityBindingRepository(
                jdbcTemplate);

        var issuer = "https://issuer.example.test";
        var subject = "synthetic-subject-001";
        var userId = UUID.randomUUID();

        persistUser(userId);

        var binding = ExternalIdentityBinding.create(
                issuer,
                subject,
                userId);

        var saved = repository.save(binding);

        var reconstructed = repository.find(
                issuer,
                subject);

        assertThat(saved)
                .isSameAs(binding);

        assertThat(reconstructed)
                .isPresent();

        assertThat(reconstructed.orElseThrow().issuer())
                .isEqualTo(issuer);

        assertThat(reconstructed.orElseThrow().subject())
                .isEqualTo(subject);

        assertThat(reconstructed.orElseThrow().userId())
                .isEqualTo(userId);
    }

    @Test
    void returnsEmptyWhenExternalIdentityDoesNotExist() {
        // Why: an unknown external identity is a normal Users persistence query
        // result.
        // Covers: exact lookup when no durable binding exists.
        // Prevents: normal absence being represented as an infrastructure error.

        var repository = new PostgreSqlExternalIdentityBindingRepository(
                jdbcTemplate);

        var result = repository.find(
                "https://issuer.example.test",
                "synthetic-unknown-subject");

        assertThat(result)
                .isEmpty();
    }

    @Test
    void lookupIsScopedToCompleteIssuerSubjectPair() {
        // Why: external identity is defined by the complete issuer/subject pair.
        // Covers: both identity components participating in the SELECT predicate.
        // Prevents: resolving a User from issuer alone or subject alone.

        var repository = new PostgreSqlExternalIdentityBindingRepository(
                jdbcTemplate);

        var issuer = "https://issuer.example.test";
        var subject = "synthetic-subject-001";
        var userId = UUID.randomUUID();

        persistUser(userId);

        repository.save(
                ExternalIdentityBinding.create(
                        issuer,
                        subject,
                        userId));

        var wrongSubject = repository.find(
                issuer,
                "synthetic-subject-002");

        var wrongIssuer = repository.find(
                "https://different-issuer.example.test",
                subject);

        assertThat(wrongSubject)
                .isEmpty();

        assertThat(wrongIssuer)
                .isEmpty();
    }

    @Test
    void preservesExternalIdentityExactlyAcrossPersistenceRoundTrip() {
        // Why: issuer and subject are provider-owned identity values whose exact
        // representation is significant.
        // Covers: persistence and reconstruction without trimming, lowercasing or
        // other normalization.
        // Prevents: JDBC/database adaptation silently changing identity semantics.

        var repository = new PostgreSqlExternalIdentityBindingRepository(
                jdbcTemplate);

        var issuer = "https://Issuer.Example.test/Identity/";
        var subject = " Synthetic-Subject:Case-Sensitive ";
        var userId = UUID.randomUUID();

        persistUser(userId);

        repository.save(
                ExternalIdentityBinding.create(
                        issuer,
                        subject,
                        userId));

        var reconstructed = repository.find(
                issuer,
                subject);

        assertThat(reconstructed)
                .isPresent();

        assertThat(reconstructed.orElseThrow().issuer())
                .isEqualTo(issuer);

        assertThat(reconstructed.orElseThrow().subject())
                .isEqualTo(subject);

        assertThat(reconstructed.orElseThrow().userId())
                .isEqualTo(userId);

        assertThat(repository.find(
                issuer.toLowerCase(),
                subject))
                .isEmpty();

        assertThat(repository.find(
                issuer,
                subject.strip()))
                .isEmpty();
    }

    /**
     * Persists one synthetic internal User required by the external identity
     * foreign-key contract.
     *
     * @param userId synthetic internal User identifier used by the current test
     */
    private void persistUser(
            UUID userId) {

        jdbcTemplate.update("""
                INSERT INTO users.users (
                    id
                )
                VALUES (?)
                """,
                userId);
    }

    @Test
    void rejectsDuplicateExternalIdentityDeterministically() {
        // Why: repeated or concurrent binding of one exact external identity must
        // produce one stable application-level conflict.
        // Covers: translation of durable (issuer, subject) uniqueness conflict.
        // Prevents: PostgreSQL duplicate-key behavior leaking through the repository
        // boundary or one identity becoming ambiguously associated with two Users.

        var repository = new PostgreSqlExternalIdentityBindingRepository(
                jdbcTemplate);

        var issuer = "https://issuer.example.test";
        var subject = "synthetic-subject-001";
        var firstUserId = UUID.randomUUID();
        var secondUserId = UUID.randomUUID();

        persistUser(firstUserId);
        persistUser(secondUserId);

        repository.save(
                ExternalIdentityBinding.create(
                        issuer,
                        subject,
                        firstUserId));

        assertThatThrownBy(() -> repository.save(
                ExternalIdentityBinding.create(
                        issuer,
                        subject,
                        secondUserId)))
                .isInstanceOf(
                        ExternalIdentityBindingAlreadyExistsException.class)
                .hasMessage(
                        "External identity binding already exists.");
    }

    @Test
    void duplicateFailureMessageDoesNotLeakExternalIdentityOrSql() {
        // Why: an authentication-identity conflict must not disclose provider-owned
        // identity values, internal User identifiers or persistence details.
        // Covers: privacy-safe duplicate-binding error contract.
        // Prevents: issuer, subject, User UUID, SQL, constraint or PostgreSQL details
        // escaping through the public exception message.

        var repository = new PostgreSqlExternalIdentityBindingRepository(
                jdbcTemplate);

        var issuer = "https://sensitive-issuer.example.test";
        var subject = "synthetic-sensitive-subject";
        var firstUserId = UUID.randomUUID();
        var secondUserId = UUID.randomUUID();

        persistUser(firstUserId);
        persistUser(secondUserId);

        repository.save(
                ExternalIdentityBinding.create(
                        issuer,
                        subject,
                        firstUserId));

        assertThatThrownBy(() -> repository.save(
                ExternalIdentityBinding.create(
                        issuer,
                        subject,
                        secondUserId)))
                .isInstanceOfSatisfying(
                        ExternalIdentityBindingAlreadyExistsException.class,
                        exception -> {

                            assertThat(exception.getMessage())
                                    .isEqualTo(
                                            "External identity binding already exists.")
                                    .doesNotContain(issuer)
                                    .doesNotContain(subject)
                                    .doesNotContain(firstUserId.toString())
                                    .doesNotContain(secondUserId.toString())
                                    .doesNotContainIgnoringCase("insert")
                                    .doesNotContainIgnoringCase(
                                            "external_identity_bindings")
                                    .doesNotContainIgnoringCase(
                                            "uq_external_identity_bindings")
                                    .doesNotContainIgnoringCase("postgres");
                        });
    }

    @Test
    void rejectsBindingForNonExistingUserWithoutLeakingPersistenceDetails() {
        // Why: the adapter must preserve the database-level User-reference invariant
        // while exposing only the infrastructure-independent repository contract.
        // Covers: translation of a foreign-key rejected INSERT.
        // Prevents: stale/arbitrary User identifiers becoming durable bindings or
        // leaking through PostgreSQL errors.

        var repository = new PostgreSqlExternalIdentityBindingRepository(
                jdbcTemplate);

        var issuer = "https://issuer.example.test";
        var subject = "synthetic-subject-001";
        var unknownUserId = UUID.randomUUID();

        assertThatThrownBy(() -> repository.save(
                ExternalIdentityBinding.create(
                        issuer,
                        subject,
                        unknownUserId)))
                .isInstanceOfSatisfying(
                        ExternalIdentityBindingPersistenceException.class,
                        exception -> {

                            assertThat(exception.getMessage())
                                    .isEqualTo(
                                            "External identity binding persistence operation failed.")
                                    .doesNotContain(issuer)
                                    .doesNotContain(subject)
                                    .doesNotContain(unknownUserId.toString())
                                    .doesNotContainIgnoringCase("insert")
                                    .doesNotContainIgnoringCase(
                                            "external_identity_bindings")
                                    .doesNotContainIgnoringCase("foreign key")
                                    .doesNotContainIgnoringCase(
                                            "fk_external_identity_bindings_user")
                                    .doesNotContainIgnoringCase("postgres");
                        });
    }

    @Test
    void translatesStorageBudgetViolationWithoutLeakingIdentity() {
        // Why: storage/index protection is an infrastructure constraint rather than
        // an authentication-facing error detail.
        // Covers: translation of PostgreSQL CHECK rejection for an oversized external
        // identity value.
        // Prevents: rejected issuer/subject values or schema constraints escaping
        // through repository failures.

        var repository = new PostgreSqlExternalIdentityBindingRepository(
                jdbcTemplate);

        var issuer = "https://issuer.example.test";
        var oversizedSubject = "s".repeat(1025);
        var userId = UUID.randomUUID();

        persistUser(userId);

        assertThatThrownBy(() -> repository.save(
                ExternalIdentityBinding.create(
                        issuer,
                        oversizedSubject,
                        userId)))
                .isInstanceOfSatisfying(
                        ExternalIdentityBindingPersistenceException.class,
                        exception -> {

                            assertThat(exception.getMessage())
                                    .isEqualTo(
                                            "External identity binding persistence operation failed.")
                                    .doesNotContain(issuer)
                                    .doesNotContain(oversizedSubject)
                                    .doesNotContain(userId.toString())
                                    .doesNotContainIgnoringCase("insert")
                                    .doesNotContainIgnoringCase("check")
                                    .doesNotContainIgnoringCase(
                                            "ck_external_identity_bindings")
                                    .doesNotContainIgnoringCase(
                                            "external_identity_bindings")
                                    .doesNotContainIgnoringCase("postgres");
                        });
    }

    @Test
    void translatesGenericWriteFailureWithoutLeakingPersistenceDetails() {
        // Why: persistence outages or schema-access failures must remain behind the
        // Users application port.
        // Covers: translation of an unrelated failed INSERT.
        // Prevents: JDBC SQL or PostgreSQL implementation details escaping through
        // write failures.

        var repository = new PostgreSqlExternalIdentityBindingRepository(
                jdbcTemplate);

        var issuer = "https://issuer.example.test";
        var subject = "synthetic-subject-001";
        var userId = UUID.randomUUID();

        try {
            jdbcTemplate.execute("""
                    ALTER TABLE users.external_identity_bindings
                    RENAME TO external_identity_bindings_unavailable
                    """);

            assertThatThrownBy(() -> repository.save(
                    ExternalIdentityBinding.create(
                            issuer,
                            subject,
                            userId)))
                    .isInstanceOfSatisfying(
                            ExternalIdentityBindingPersistenceException.class,
                            exception -> {

                                assertThat(exception.getMessage())
                                        .isEqualTo(
                                                "External identity binding persistence operation failed.")
                                        .doesNotContain(issuer)
                                        .doesNotContain(subject)
                                        .doesNotContain(userId.toString())
                                        .doesNotContainIgnoringCase("insert")
                                        .doesNotContainIgnoringCase(
                                                "external_identity_bindings")
                                        .doesNotContainIgnoringCase("postgres");
                            });
        } finally {
            jdbcTemplate.execute("""
                    ALTER TABLE IF EXISTS users.external_identity_bindings_unavailable
                    RENAME TO external_identity_bindings
                    """);
        }
    }

    @Test
    void translatesReadFailureWithoutLeakingPersistenceDetails() {
        // Why: reads require the same stable infrastructure boundary as writes.
        // Covers: translation of a failed exact-identity SELECT.
        // Prevents: JDBC SQL, provider identity values or PostgreSQL implementation
        // details escaping through lookup failures.

        var repository = new PostgreSqlExternalIdentityBindingRepository(
                jdbcTemplate);

        var issuer = "https://issuer.example.test";
        var subject = "synthetic-subject-001";

        try {
            jdbcTemplate.execute("""
                    ALTER TABLE users.external_identity_bindings
                    RENAME TO external_identity_bindings_unavailable
                    """);

            assertThatThrownBy(() -> repository.find(
                    issuer,
                    subject))
                    .isInstanceOfSatisfying(
                            ExternalIdentityBindingPersistenceException.class,
                            exception -> {

                                assertThat(exception.getMessage())
                                        .isEqualTo(
                                                "External identity binding persistence operation failed.")
                                        .doesNotContain(issuer)
                                        .doesNotContain(subject)
                                        .doesNotContainIgnoringCase("select")
                                        .doesNotContainIgnoringCase(
                                                "external_identity_bindings")
                                        .doesNotContainIgnoringCase("postgres");
                            });
        } finally {
            jdbcTemplate.execute("""
                    ALTER TABLE IF EXISTS users.external_identity_bindings_unavailable
                    RENAME TO external_identity_bindings
                    """);
        }
    }
}
