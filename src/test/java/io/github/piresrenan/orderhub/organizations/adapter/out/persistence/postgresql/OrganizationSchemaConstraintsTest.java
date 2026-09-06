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
class OrganizationSchemaConstraintsTest {

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
    void clearOrganizations() {

        jdbcTemplate.update(
                "TRUNCATE TABLE organizations.organizations");
    }

    @Test
    void databaseRequiresOrganizationStatus() {

        assertThatThrownBy(() ->
                jdbcTemplate.update(
                        """
                        INSERT INTO organizations.organizations (
                            id,
                            name,
                            status
                        )
                        VALUES (?, ?, ?)
                        """,
                        UUID.randomUUID(),
                        "Acme Group",
                        null))
                .isInstanceOf(
                        DataIntegrityViolationException.class);
    }

    @Test
    void databaseRejectsUnknownOrganizationStatus() {

        assertThatThrownBy(() ->
                jdbcTemplate.update(
                        """
                        INSERT INTO organizations.organizations (
                            id,
                            name,
                            status
                        )
                        VALUES (?, ?, ?)
                        """,
                        UUID.randomUUID(),
                        "Acme Group",
                        "UNKNOWN"))
                .isInstanceOf(
                        DataIntegrityViolationException.class);
    }

    @Test
    void databaseRejectsBlankOrganizationName() {

        assertThatThrownBy(() ->
                insertOrganization(
                        "   ",
                        "ACTIVE"))
                .isInstanceOf(
                        DataIntegrityViolationException.class);
    }

    @Test
    void databaseRejectsNonCanonicalOrganizationName() {

        assertThatThrownBy(() ->
                insertOrganization(
                        " Acme Group ",
                        "ACTIVE"))
                .isInstanceOf(
                        DataIntegrityViolationException.class);
    }

    @Test
    void databaseRejectsOrganizationNameBeyondCodePointLimit() {

        var tooLongName =
                "x".repeat(
                        121);

        assertThatThrownBy(() ->
                insertOrganization(
                        tooLongName,
                        "ACTIVE"))
                .isInstanceOf(
                        DataIntegrityViolationException.class);
    }

    @Test
    void statusColumnHasNoImplicitDefault() {

        var columnDefault =
                jdbcTemplate.queryForObject(
                        """
                        SELECT column_default
                        FROM information_schema.columns
                        WHERE table_schema = 'organizations'
                          AND table_name = 'organizations'
                          AND column_name = 'status'
                        """,
                        String.class);

        assertThat(columnDefault)
                .isNull();
    }

    private void insertOrganization(
            String name,
            String status) {

        jdbcTemplate.update(
                """
                INSERT INTO organizations.organizations (
                    id,
                    name,
                    status
                )
                VALUES (?, ?, ?)
                """,
                UUID.randomUUID(),
                name,
                status);
    }
}
