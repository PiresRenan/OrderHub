package io.github.piresrenan.orderhub.workforce.adapter.out.persistence.postgresql;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import javax.sql.DataSource;

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

import io.github.piresrenan.orderhub.authorization.domain.model.AuthorityBand;
import io.github.piresrenan.orderhub.workforce.application.model.WorkforceAuditActionType;
import io.github.piresrenan.orderhub.workforce.application.model.WorkforceAuditEvidence;
import io.github.piresrenan.orderhub.workforce.application.model.WorkforceAuditOutcome;
import io.github.piresrenan.orderhub.workforce.application.model.WorkforceAuditState;
import io.github.piresrenan.orderhub.workforce.application.port.in.ResolveWorkforceAuthorityChangeAnalyticsSourceUseCase;
import io.github.piresrenan.orderhub.workforce.application.port.in.WorkforceAuthorityChangeAnalyticsSource;
import io.github.piresrenan.orderhub.workforce.application.port.out.WorkforceAuditRepository;
import io.github.piresrenan.orderhub.workforce.application.port.out.WorkforceAuthorityChangeAnalyticsSourceRepository;
import io.github.piresrenan.orderhub.workforce.application.service.ResolveWorkforceAuthorityChangeAnalyticsSourceService;
import io.github.piresrenan.orderhub.workforce.domain.model.StaffStatus;

@Testcontainers
class PostgreSqlWorkforceAuthorityChangeAnalyticsSourceTest {

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

    private static final UUID TENANT_ID =
            UUID.fromString("00000000-0000-4000-8000-0000000000a1");

    private static final UUID OTHER_TENANT_ID =
            UUID.fromString("00000000-0000-4000-8000-0000000000a2");

    private static final UUID AUDIT_EVENT_ID =
            UUID.fromString("00000000-0000-4000-8000-0000000000b1");

    private static final UUID UNKNOWN_AUDIT_EVENT_ID =
            UUID.fromString("00000000-0000-4000-8000-0000000000b2");

    private static final UUID ACTOR_STAFF_ID =
            UUID.fromString("00000000-0000-4000-8000-0000000000c1");

    private static final UUID AFFECTED_STAFF_ID =
            UUID.fromString("00000000-0000-4000-8000-0000000000d1");

    private static final UUID CORRELATION_ID =
            UUID.fromString("00000000-0000-4000-8000-0000000000e1");

    private static final UUID DEPARTMENT_ID =
            UUID.fromString("00000000-0000-4000-8000-0000000000f1");

    private static final UUID BEFORE_POSITION_ID =
            UUID.fromString("00000000-0000-4000-8000-0000000000f2");

    private static final UUID AFTER_POSITION_ID =
            UUID.fromString("00000000-0000-4000-8000-0000000000f3");

    private static final String REASON_CODE =
            "AUTHORITY_BAND_RAISED";

    private static JdbcTemplate jdbcTemplate;

    private WorkforceAuditRepository auditRepository;

    private ResolveWorkforceAuthorityChangeAnalyticsSourceUseCase
            resolveAnalyticsSource;

    @BeforeAll
    static void migrateSchema() {

        DataSource dataSource =
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
    void resetAuditStorage() {

        jdbcTemplate.update(
                "TRUNCATE TABLE workforce.audit_events");

        auditRepository =
                new PostgreSqlWorkforceAuditRepository(
                        jdbcTemplate);

        WorkforceAuthorityChangeAnalyticsSourceRepository sourceRepository =
                new PostgreSqlWorkforceAuthorityChangeAnalyticsSourceRepository(
                        jdbcTemplate);

        resolveAnalyticsSource =
                new ResolveWorkforceAuthorityChangeAnalyticsSourceService(
                        sourceRepository);
    }

    @Test
    void returnsBoundedTenantScopedSourceWithPersistedOccurrenceTime() {
        // Why: analytics needs the committed operational evidence behind one
        // audit event, but must never reach into workforce persistence to get
        // it. Workforce therefore owns a purpose-built inbound contract that
        // exposes only what the accepted analytical fact requires, and that
        // carries the occurrence time the operational source already stored
        // rather than any time analytics could invent.
        // Covers: resolution by Tenant and audit event through the workforce
        // application boundary, the exact bounded projection returned, the
        // persisted occurrence time crossing the contract unchanged, and
        // absence for a foreign Tenant or an unknown event.
        // Prevents: analytics reading workforce tables, organizational state
        // and correlation identity leaking into the contract, a substituted
        // occurrence time, and a Tenant enumeration side channel.
        //
        // The evidence is seeded through the accepted append-only audit port,
        // so the contract is proven against a row the production path actually
        // wrote. The occurrence time is read back from PostgreSQL rather than
        // asserted against a Java literal, because the column is generated by
        // the database.

        auditRepository.append(
                new WorkforceAuditEvidence(
                        AUDIT_EVENT_ID,
                        TENANT_ID,
                        ACTOR_STAFF_ID,
                        AFFECTED_STAFF_ID,
                        WorkforceAuditActionType.POSITION_AUTHORITY_CHANGED,
                        WorkforceAuditOutcome.APPLIED,
                        REASON_CODE,
                        CORRELATION_ID,
                        new WorkforceAuditState(
                                StaffStatus.ACTIVE,
                                DEPARTMENT_ID,
                                BEFORE_POSITION_ID,
                                null,
                                AuthorityBand.OPERATIONAL),
                        new WorkforceAuditState(
                                StaffStatus.ACTIVE,
                                DEPARTMENT_ID,
                                AFTER_POSITION_ID,
                                null,
                                AuthorityBand.SUPERVISORY)));

        var persistedOccurredAt =
                persistedOccurredAt(
                        TENANT_ID,
                        AUDIT_EVENT_ID);

        var resolved =
                resolveAnalyticsSource.resolve(
                        TENANT_ID,
                        AUDIT_EVENT_ID);

        assertThat(resolved)
                .as("A committed audit event must be resolvable through the"
                        + " workforce analytics source contract")
                .contains(
                        new WorkforceAuthorityChangeAnalyticsSource(
                                TENANT_ID,
                                AUDIT_EVENT_ID,
                                ACTOR_STAFF_ID,
                                AFFECTED_STAFF_ID,
                                WorkforceAuditActionType.POSITION_AUTHORITY_CHANGED,
                                WorkforceAuditOutcome.APPLIED,
                                REASON_CODE,
                                persistedOccurredAt));

        assertThat(
                resolveAnalyticsSource.resolve(
                        OTHER_TENANT_ID,
                        AUDIT_EVENT_ID))
                .as("The same audit event must not be resolvable from another"
                        + " Tenant, so the contract is no enumeration channel")
                .isEmpty();

        assertThat(
                resolveAnalyticsSource.resolve(
                        TENANT_ID,
                        UNKNOWN_AUDIT_EVENT_ID))
                .as("An unknown audit event must be absent in exactly the same"
                        + " way as a foreign Tenant's event")
                .isEmpty();
    }

    /**
     * Reads the occurrence time PostgreSQL persisted for one Tenant-scoped
     * audit event.
     *
     * <p>
     * The column is database-generated, so no Java literal can predict it. The
     * contract's value is compared against this persisted value instead, which
     * keeps the comparison exact and free of any truncation rule.
     * </p>
     */
    private static Instant persistedOccurredAt(
            UUID tenantId,
            UUID auditEventId) {

        return jdbcTemplate.queryForObject(
                        """
                        SELECT occurred_at
                        FROM workforce.audit_events
                        WHERE tenant_id = ?
                          AND audit_event_id = ?
                        """,
                        Timestamp.class,
                        tenantId,
                        auditEventId)
                .toInstant();
    }
}
