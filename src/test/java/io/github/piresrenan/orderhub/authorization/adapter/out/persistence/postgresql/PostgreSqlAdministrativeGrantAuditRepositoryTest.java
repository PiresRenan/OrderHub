package io.github.piresrenan.orderhub.authorization.adapter.out.persistence.postgresql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
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

import io.github.piresrenan.orderhub.authorization.application.model.AdministrativeGrantAuditAction;
import io.github.piresrenan.orderhub.authorization.application.model.AdministrativeGrantAuditEvidence;
import io.github.piresrenan.orderhub.authorization.application.model.AdministrativeGrantAuditOutcome;
import io.github.piresrenan.orderhub.authorization.application.port.out.AuthorizationPersistenceException;
import io.github.piresrenan.orderhub.authorization.domain.model.AdministrativeScope;
import io.github.piresrenan.orderhub.authorization.domain.model.PermissionCode;

@Testcontainers
class PostgreSqlAdministrativeGrantAuditRepositoryTest {

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
    private static PostgreSqlAdministrativeGrantAuditRepository repository;

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
                new PostgreSqlAdministrativeGrantAuditRepository(
                        jdbcTemplate);
    }

    @BeforeEach
    void clearAudit() {

        jdbcTemplate.update(
                """
                ALTER TABLE access_control.administrative_grant_audit_events
                    DISABLE TRIGGER trg_authorization_administrative_grant_audit_append_only
                """);

        jdbcTemplate.update(
                "DELETE FROM access_control.administrative_grant_audit_events");

        jdbcTemplate.update(
                """
                ALTER TABLE access_control.administrative_grant_audit_events
                    ENABLE TRIGGER trg_authorization_administrative_grant_audit_append_only
                """);
    }

    @Test
    void appendsExactBoundedEvidence() {

        var evidence =
                evidence(
                        UUID.randomUUID(),
                        UUID.randomUUID());

        repository.append(
                evidence);

        var persisted =
                jdbcTemplate.queryForMap(
                        """
                        SELECT
                            actor_user_id,
                            target_user_id,
                            scope_type,
                            scope_id,
                            permission_code,
                            action_type,
                            outcome,
                            correlation_id,
                            before_granted,
                            after_granted
                        FROM access_control.administrative_grant_audit_events
                        WHERE audit_event_id = ?
                        """,
                        evidence.auditEventId());

        assertThat(persisted.get(
                "actor_user_id"))
                .isEqualTo(
                        evidence.actorUserId());

        assertThat(persisted.get(
                "target_user_id"))
                .isEqualTo(
                        evidence.targetUserId());

        assertThat(persisted.get(
                "scope_type"))
                .isEqualTo(
                        "PLATFORM");

        assertThat(persisted.get(
                "scope_id"))
                .isNull();

        assertThat(persisted.get(
                "permission_code"))
                .isEqualTo(
                        "PLATFORM_ORGANIZATIONS_VIEW");

        assertThat(persisted.get(
                "action_type"))
                .isEqualTo(
                        "GRANT_PERMISSION");

        assertThat(persisted.get(
                "outcome"))
                .isEqualTo(
                        "APPLIED");

        assertThat(persisted.get(
                "correlation_id"))
                .isEqualTo(
                        evidence.correlationId());

        assertThat(persisted.get(
                "before_granted"))
                .isEqualTo(
                        false);

        assertThat(persisted.get(
                "after_granted"))
                .isEqualTo(
                        true);
    }

    @Test
    void databaseOwnsOccurrenceTime() {

        var evidence =
                evidence(
                        UUID.randomUUID(),
                        UUID.randomUUID());

        repository.append(
                evidence);

        assertThat(
                jdbcTemplate.queryForObject(
                        """
                        SELECT occurred_at
                        FROM access_control.administrative_grant_audit_events
                        WHERE audit_event_id = ?
                        """,
                        OffsetDateTime.class,
                        evidence.auditEventId()))
                .isNotNull();
    }

    @Test
    void sameCorrelationMayContainMultipleDistinctAuditFacts() {

        var correlationId =
                UUID.randomUUID();

        repository.append(
                evidence(
                        UUID.randomUUID(),
                        correlationId));

        repository.append(
                evidence(
                        UUID.randomUUID(),
                        correlationId));

        assertThat(
                jdbcTemplate.queryForObject(
                        """
                        SELECT count(*)
                        FROM access_control.administrative_grant_audit_events
                        WHERE correlation_id = ?
                        """,
                        Integer.class,
                        correlationId))
                .isEqualTo(
                        2);
    }

    @Test
    void duplicateAuditIdentityFailsWithSanitizedPersistenceException() {

        var auditEventId =
                UUID.randomUUID();

        var evidence =
                evidence(
                        auditEventId,
                        UUID.randomUUID());

        repository.append(
                evidence);

        assertThatThrownBy(() ->
                repository.append(
                        evidence))
                .isInstanceOf(
                        AuthorizationPersistenceException.class)
                .hasMessage(
                        "Authorization persistence operation failed");
    }

    @Test
    void rejectsMissingEvidence() {

        assertThatThrownBy(() ->
                repository.append(
                        null))
                .isInstanceOf(
                        NullPointerException.class)
                .hasMessage(
                        "evidence");
    }

    private static AdministrativeGrantAuditEvidence evidence(
            UUID auditEventId,
            UUID correlationId) {

        return new AdministrativeGrantAuditEvidence(
                auditEventId,
                UUID.randomUUID(),
                UUID.randomUUID(),
                AdministrativeScope.platform(),
                PermissionCode.PLATFORM_ORGANIZATIONS_VIEW,
                AdministrativeGrantAuditAction.GRANT_PERMISSION,
                AdministrativeGrantAuditOutcome.APPLIED,
                correlationId,
                false,
                true);
    }
}
