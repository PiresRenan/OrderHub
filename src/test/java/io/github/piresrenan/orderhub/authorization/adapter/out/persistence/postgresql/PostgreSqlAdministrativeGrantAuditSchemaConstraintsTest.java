package io.github.piresrenan.orderhub.authorization.adapter.out.persistence.postgresql;

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
class PostgreSqlAdministrativeGrantAuditSchemaConstraintsTest {

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
                new JdbcTemplate(
                        dataSource);
    }

    @BeforeEach
    void clearAuditRows() {

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
    void auditEvidenceIsAppendOnlyAgainstUpdate() {

        var auditId =
                insertValidPlatformGrantAudit();

        assertThatThrownBy(() ->
                jdbcTemplate.update(
                        """
                        UPDATE access_control.administrative_grant_audit_events
                        SET outcome = 'NO_CHANGE'
                        WHERE audit_event_id = ?
                        """,
                        auditId))
                .isInstanceOf(
                        DataIntegrityViolationException.class);
    }

    @Test
    void auditEvidenceIsAppendOnlyAgainstDelete() {

        var auditId =
                insertValidPlatformGrantAudit();

        assertThatThrownBy(() ->
                jdbcTemplate.update(
                        """
                        DELETE FROM access_control.administrative_grant_audit_events
                        WHERE audit_event_id = ?
                        """,
                        auditId))
                .isInstanceOf(
                        DataIntegrityViolationException.class);
    }

    @Test
    void platformScopeRejectsScopedIdentity() {

        assertThatThrownBy(() ->
                insertAudit(
                        "PLATFORM",
                        UUID.randomUUID(),
                        "PLATFORM_ORGANIZATIONS_VIEW",
                        "GRANT_PERMISSION",
                        "APPLIED",
                        false,
                        true))
                .isInstanceOf(
                        DataIntegrityViolationException.class);
    }

    @Test
    void organizationScopeRequiresScopedIdentity() {

        assertThatThrownBy(() ->
                insertAudit(
                        "ORGANIZATION",
                        null,
                        "ORGANIZATION_TENANTS_VIEW",
                        "GRANT_PERMISSION",
                        "APPLIED",
                        false,
                        true))
                .isInstanceOf(
                        DataIntegrityViolationException.class);
    }

    @Test
    void permissionMustMatchAdministrativeScope() {

        assertThatThrownBy(() ->
                insertAudit(
                        "ORGANIZATION",
                        UUID.randomUUID(),
                        "PLATFORM_ORGANIZATIONS_VIEW",
                        "GRANT_PERMISSION",
                        "APPLIED",
                        false,
                        true))
                .isInstanceOf(
                        DataIntegrityViolationException.class);
    }

    @Test
    void tenantBusinessPermissionIsRejectedFromAdministrativeAudit() {

        assertThatThrownBy(() ->
                insertAudit(
                        "PLATFORM",
                        null,
                        "ORDERS_VIEW",
                        "GRANT_PERMISSION",
                        "APPLIED",
                        false,
                        true))
                .isInstanceOf(
                        DataIntegrityViolationException.class);
    }

    @Test
    void unknownActionIsRejected() {

        assertThatThrownBy(() ->
                insertAudit(
                        "PLATFORM",
                        null,
                        "PLATFORM_ORGANIZATIONS_VIEW",
                        "UNKNOWN_ACTION",
                        "APPLIED",
                        false,
                        true))
                .isInstanceOf(
                        DataIntegrityViolationException.class);
    }

    @Test
    void actionMustMatchAfterState() {

        assertThatThrownBy(() ->
                insertAudit(
                        "PLATFORM",
                        null,
                        "PLATFORM_ORGANIZATIONS_VIEW",
                        "GRANT_PERMISSION",
                        "APPLIED",
                        true,
                        false))
                .isInstanceOf(
                        DataIntegrityViolationException.class);
    }

    @Test
    void outcomeMustDescribeWhetherStateChanged() {

        assertThatThrownBy(() ->
                insertAudit(
                        "PLATFORM",
                        null,
                        "PLATFORM_ORGANIZATIONS_VIEW",
                        "GRANT_PERMISSION",
                        "APPLIED",
                        true,
                        true))
                .isInstanceOf(
                        DataIntegrityViolationException.class);

        assertThatThrownBy(() ->
                insertAudit(
                        "PLATFORM",
                        null,
                        "PLATFORM_ORGANIZATIONS_VIEW",
                        "GRANT_PERMISSION",
                        "NO_CHANGE",
                        false,
                        true))
                .isInstanceOf(
                        DataIntegrityViolationException.class);
    }

    @Test
    void auditTableCreatesNoCrossSchemaForeignKeys() {

        var foreignKeyCount =
                jdbcTemplate.queryForObject(
                        """
                        SELECT count(*)
                        FROM pg_constraint constraint_definition
                        JOIN pg_class source_table
                          ON source_table.oid = constraint_definition.conrelid
                        JOIN pg_namespace source_schema
                          ON source_schema.oid = source_table.relnamespace
                        WHERE constraint_definition.contype = 'f'
                          AND source_schema.nspname = 'access_control'
                          AND source_table.relname =
                              'administrative_grant_audit_events'
                        """,
                        Integer.class);

        assertThat(foreignKeyCount)
                .isZero();
    }

    private static UUID insertValidPlatformGrantAudit() {

        return insertAudit(
                "PLATFORM",
                null,
                "PLATFORM_ORGANIZATIONS_VIEW",
                "GRANT_PERMISSION",
                "APPLIED",
                false,
                true);
    }

    private static UUID insertAudit(
            String scopeType,
            UUID scopeId,
            String permissionCode,
            String action,
            String outcome,
            boolean beforeGranted,
            boolean afterGranted) {

        var auditId =
                UUID.randomUUID();

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
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                auditId,
                UUID.randomUUID(),
                UUID.randomUUID(),
                scopeType,
                scopeId,
                permissionCode,
                action,
                outcome,
                UUID.randomUUID(),
                beforeGranted,
                afterGranted);

        return auditId;
    }
}
