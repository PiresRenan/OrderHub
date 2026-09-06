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

import io.github.piresrenan.orderhub.authorization.application.port.out.AdministrativeGrantMutationResult;
import io.github.piresrenan.orderhub.authorization.domain.model.AdministrativeGrant;
import io.github.piresrenan.orderhub.authorization.domain.model.AdministrativeScope;
import io.github.piresrenan.orderhub.authorization.domain.model.PermissionCode;

@Testcontainers
class PostgreSqlAdministrativeGrantRepositoryTest {

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
    private static PostgreSqlAdministrativeGrantRepository repository;

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

        repository =
                new PostgreSqlAdministrativeGrantRepository(
                        jdbcTemplate);
    }

    @BeforeEach
    void clearGrants() {

        jdbcTemplate.update(
                "DELETE FROM access_control.administrative_grants");
    }

    @Test
    void grantsPlatformAuthorityAndFindsExactGrant() {

        var grant =
                platformGrant(
                        UUID.randomUUID());

        assertThat(
                repository.grant(
                        grant))
                .isEqualTo(
                        AdministrativeGrantMutationResult.GRANTED);

        assertThat(
                repository.exists(
                        grant))
                .isTrue();
    }

    @Test
    void grantingExistingAuthorityIsIdempotent() {

        var grant =
                platformGrant(
                        UUID.randomUUID());

        assertThat(
                repository.grant(
                        grant))
                .isEqualTo(
                        AdministrativeGrantMutationResult.GRANTED);

        assertThat(
                repository.grant(
                        grant))
                .isEqualTo(
                        AdministrativeGrantMutationResult.ALREADY_GRANTED);

        assertThat(
                countGrantRows())
                .isEqualTo(
                        1);
    }

    @Test
    void persistsExactOrganizationScopeIdentity() {

        var organizationId =
                UUID.randomUUID();

        var grant =
                new AdministrativeGrant(
                        UUID.randomUUID(),
                        AdministrativeScope.organization(
                                organizationId),
                        PermissionCode.ORGANIZATION_TENANTS_VIEW);

        assertThat(
                repository.grant(
                        grant))
                .isEqualTo(
                        AdministrativeGrantMutationResult.GRANTED);

        assertThat(
                jdbcTemplate.queryForObject(
                        """
                        SELECT scope_id
                        FROM access_control.administrative_grants
                        WHERE user_id = ?
                        """,
                        UUID.class,
                        grant.userId()))
                .isEqualTo(
                        organizationId);
    }

    @Test
    void revokesExistingGrant() {

        var grant =
                platformGrant(
                        UUID.randomUUID());

        repository.grant(
                grant);

        assertThat(
                repository.revoke(
                        grant))
                .isEqualTo(
                        AdministrativeGrantMutationResult.REVOKED);

        assertThat(
                repository.exists(
                        grant))
                .isFalse();
    }

    @Test
    void revokingAbsentGrantIsIdempotent() {

        assertThat(
                repository.revoke(
                        platformGrant(
                                UUID.randomUUID())))
                .isEqualTo(
                        AdministrativeGrantMutationResult.ALREADY_ABSENT);
    }

    @Test
    void exactOrganizationScopeDoesNotBleedAcrossOrganizations() {

        var userId =
                UUID.randomUUID();

        var organizationA =
                UUID.randomUUID();

        var organizationB =
                UUID.randomUUID();

        var grantA =
                new AdministrativeGrant(
                        userId,
                        AdministrativeScope.organization(
                                organizationA),
                        PermissionCode.ORGANIZATION_TENANTS_VIEW);

        var grantB =
                new AdministrativeGrant(
                        userId,
                        AdministrativeScope.organization(
                                organizationB),
                        PermissionCode.ORGANIZATION_TENANTS_VIEW);

        repository.grant(
                grantA);

        assertThat(
                repository.exists(
                        grantA))
                .isTrue();

        assertThat(
                repository.exists(
                        grantB))
                .isFalse();
    }

    @Test
    void rejectsMissingGrantArgument() {

        assertThatThrownBy(() ->
                repository.grant(
                        null))
                .isInstanceOf(
                        NullPointerException.class)
                .hasMessage(
                        "grant");
    }

    private static AdministrativeGrant platformGrant(
            UUID userId) {

        return new AdministrativeGrant(
                userId,
                AdministrativeScope.platform(),
                PermissionCode.PLATFORM_ORGANIZATIONS_VIEW);
    }

    private static int countGrantRows() {

        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM access_control.administrative_grants",
                Integer.class);
    }
}
