package io.github.piresrenan.orderhub.authorization.adapter.out.persistence.postgresql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayDeque;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import io.github.piresrenan.orderhub.authorization.adapter.out.transaction.spring.SpringAuthorizationTransactionExecutor;
import io.github.piresrenan.orderhub.authorization.application.port.out.AdministrativeGrantMutationResult;
import io.github.piresrenan.orderhub.authorization.application.port.out.AuthorizationPersistenceException;
import io.github.piresrenan.orderhub.authorization.application.service.AuditedAdministrativeGrantMutationService;
import io.github.piresrenan.orderhub.authorization.domain.model.AdministrativeGrant;
import io.github.piresrenan.orderhub.authorization.domain.model.AdministrativeScope;
import io.github.piresrenan.orderhub.authorization.domain.model.PermissionCode;

@Testcontainers
class AdministrativeGrantAuditTransactionIntegrationTest {

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
    private static PostgreSqlAdministrativeGrantRepository grants;
    private static PostgreSqlAdministrativeGrantAuditRepository audit;
    private static SpringAuthorizationTransactionExecutor transactions;

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

        grants =
                new PostgreSqlAdministrativeGrantRepository(
                        jdbcTemplate);

        audit =
                new PostgreSqlAdministrativeGrantAuditRepository(
                        jdbcTemplate);

        transactions =
                new SpringAuthorizationTransactionExecutor(
                        new DataSourceTransactionManager(
                                dataSource));
    }

    @BeforeEach
    void clearState() {

        jdbcTemplate.update(
                "DELETE FROM access_control.administrative_grants");

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
    void grantAndAuditCommitTogether() {

        var grant =
                platformGrant();

        var service =
                serviceWithIds(
                        UUID.randomUUID());

        assertThat(
                service.grant(
                        UUID.randomUUID(),
                        grant,
                        UUID.randomUUID()))
                .isEqualTo(
                        AdministrativeGrantMutationResult.GRANTED);

        assertThat(
                grants.exists(
                        grant))
                .isTrue();

        assertThat(
                auditCount())
                .isEqualTo(
                        1);
    }

    @Test
    void revokeAndAuditCommitTogether() {

        var grant =
                platformGrant();

        grants.grant(
                grant);

        var service =
                serviceWithIds(
                        UUID.randomUUID());

        assertThat(
                service.revoke(
                        UUID.randomUUID(),
                        grant,
                        UUID.randomUUID()))
                .isEqualTo(
                        AdministrativeGrantMutationResult.REVOKED);

        assertThat(
                grants.exists(
                        grant))
                .isFalse();

        assertThat(
                auditCount())
                .isEqualTo(
                        1);
    }

    @Test
    void auditPersistenceFailureRollsBackGrant() {

        var duplicateAuditId =
                UUID.randomUUID();

        seedAuditId(
                duplicateAuditId);

        var grant =
                platformGrant();

        var service =
                serviceWithIds(
                        duplicateAuditId);

        assertThatThrownBy(() ->
                service.grant(
                        UUID.randomUUID(),
                        grant,
                        UUID.randomUUID()))
                .isInstanceOf(
                        AuthorizationPersistenceException.class);

        assertThat(
                grants.exists(
                        grant))
                .isFalse();

        assertThat(
                auditCount())
                .isEqualTo(
                        1);
    }

    @Test
    void auditPersistenceFailureRollsBackRevoke() {

        var duplicateAuditId =
                UUID.randomUUID();

        seedAuditId(
                duplicateAuditId);

        var grant =
                platformGrant();

        grants.grant(
                grant);

        var service =
                serviceWithIds(
                        duplicateAuditId);

        assertThatThrownBy(() ->
                service.revoke(
                        UUID.randomUUID(),
                        grant,
                        UUID.randomUUID()))
                .isInstanceOf(
                        AuthorizationPersistenceException.class);

        assertThat(
                grants.exists(
                        grant))
                .isTrue();

        assertThat(
                auditCount())
                .isEqualTo(
                        1);
    }

    @Test
    void repeatedGrantAuditsNoChangeWithoutFalseTransition() {

        var firstAudit =
                UUID.randomUUID();

        var secondAudit =
                UUID.randomUUID();

        var service =
                serviceWithIds(
                        firstAudit,
                        secondAudit);

        var grant =
                platformGrant();

        service.grant(
                UUID.randomUUID(),
                grant,
                UUID.randomUUID());

        service.grant(
                UUID.randomUUID(),
                grant,
                UUID.randomUUID());

        var second =
                jdbcTemplate.queryForMap(
                        """
                        SELECT
                            outcome,
                            before_granted,
                            after_granted
                        FROM access_control.administrative_grant_audit_events
                        WHERE audit_event_id = ?
                        """,
                        secondAudit);

        assertThat(second.get(
                "outcome"))
                .isEqualTo(
                        "NO_CHANGE");

        assertThat(second.get(
                "before_granted"))
                .isEqualTo(
                        true);

        assertThat(second.get(
                "after_granted"))
                .isEqualTo(
                        true);
    }

    @Test
    void repeatedRevokeAuditsNoChangeWithoutFalseTransition() {

        var firstAudit =
                UUID.randomUUID();

        var secondAudit =
                UUID.randomUUID();

        var service =
                serviceWithIds(
                        firstAudit,
                        secondAudit);

        var grant =
                platformGrant();

        grants.grant(
                grant);

        service.revoke(
                UUID.randomUUID(),
                grant,
                UUID.randomUUID());

        service.revoke(
                UUID.randomUUID(),
                grant,
                UUID.randomUUID());

        var second =
                jdbcTemplate.queryForMap(
                        """
                        SELECT
                            outcome,
                            before_granted,
                            after_granted
                        FROM access_control.administrative_grant_audit_events
                        WHERE audit_event_id = ?
                        """,
                        secondAudit);

        assertThat(second.get(
                "outcome"))
                .isEqualTo(
                        "NO_CHANGE");

        assertThat(second.get(
                "before_granted"))
                .isEqualTo(
                        false);

        assertThat(second.get(
                "after_granted"))
                .isEqualTo(
                        false);
    }

    private static AuditedAdministrativeGrantMutationService serviceWithIds(
            UUID... auditIds) {

        var ids =
                new ArrayDeque<UUID>();

        for (var auditId : auditIds) {
            ids.add(
                    auditId);
        }

        return new AuditedAdministrativeGrantMutationService(
                transactions,
                grants,
                audit,
                ids::removeFirst);
    }

    private static AdministrativeGrant platformGrant() {

        return new AdministrativeGrant(
                UUID.randomUUID(),
                AdministrativeScope.platform(),
                PermissionCode.PLATFORM_ORGANIZATIONS_VIEW);
    }

    private static int auditCount() {

        return jdbcTemplate.queryForObject(
                """
                SELECT count(*)
                FROM access_control.administrative_grant_audit_events
                """,
                Integer.class);
    }

    private static void seedAuditId(
            UUID auditEventId) {

        jdbcTemplate.update(
                """
                INSERT INTO access_control.administrative_grant_audit_events (
                    audit_event_id,
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
                )
                VALUES (
                    ?,
                    ?,
                    ?,
                    'PLATFORM',
                    NULL,
                    'PLATFORM_ORGANIZATIONS_VIEW',
                    'GRANT_PERMISSION',
                    'APPLIED',
                    ?,
                    FALSE,
                    TRUE
                )
                """,
                auditEventId,
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID());
    }
}
