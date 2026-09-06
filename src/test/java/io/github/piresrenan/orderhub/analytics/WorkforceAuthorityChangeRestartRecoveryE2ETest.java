package io.github.piresrenan.orderhub.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import io.github.piresrenan.orderhub.OrderHubApplication;
import io.github.piresrenan.orderhub.analytics.adapter.in.event.spring.WorkforceAuthorityChangeAuditRecordedListener;
import io.github.piresrenan.orderhub.analytics.adapter.out.persistence.postgresql.PostgreSqlWorkforceAuthorityChangeFactRepository;
import io.github.piresrenan.orderhub.analytics.application.port.out.WorkforceAuthorityChangeFactRepository;
import io.github.piresrenan.orderhub.authorization.domain.model.PermissionCode;
import io.github.piresrenan.orderhub.authorization.domain.model.PermissionEnvelope;
import io.github.piresrenan.orderhub.workforce.application.model.PrivilegedPositionChangeCommand;
import io.github.piresrenan.orderhub.workforce.application.service.PrivilegedPositionChangeExecutionService;
import io.github.piresrenan.orderhub.workforce.domain.model.WorkforceMutationDecision;

/**
 * Proves that a durable publication left outstanding by one application
 * lifecycle is projected automatically by the next one.
 *
 * <p>
 * This is the deployable answer to crash recovery. A process can stop after the
 * source transaction committed its publication but before the asynchronous
 * listener completed, and OrderHub exposes only the health endpoint, so nothing
 * remote can be asked to drive resubmission. Startup republication is therefore
 * the recovery path an operator actually has, and it has to be proven by
 * restarting rather than by calling the framework API from a test.
 * </p>
 *
 * <p>
 * Two independent Spring application contexts run in sequence against one
 * PostgreSQL database. The second one is started with the real production
 * configuration and is never asked to recover anything: no resubmission API is
 * invoked here, so whatever recovers the fact is the startup mechanism itself.
 * </p>
 */
class WorkforceAuthorityChangeRestartRecoveryE2ETest {

    private static final DockerImageName POSTGRES_IMAGE =
            DockerImageName.parse(
                            "postgres:18.6-trixie@sha256:"
                                    + "4ef4dbc939d61acea57712655ddb4b4ab27419c9"
                                    + "13f94cca0cd57cb3ea3c2280")
                    .asCompatibleSubstituteFor("postgres");

    private static final Duration SETTLE_TIMEOUT =
            Duration.ofSeconds(60);

    private static final Duration POLL_INTERVAL =
            Duration.ofMillis(200);

    private static final String ANALYTICS_LISTENER_ID =
            WorkforceAuthorityChangeAuditRecordedListener.LISTENER_ID;

    private static PostgreSQLContainer postgres;

    @BeforeAll
    static void startSharedDatabase() {

        postgres =
                new PostgreSQLContainer(POSTGRES_IMAGE)
                        .withDatabaseName("orderhub_test")
                        .withUsername("orderhub_test")
                        .withPassword("synthetic-test-password");

        postgres.start();
    }

    @AfterAll
    static void stopSharedDatabase() {

        if (postgres != null) {
            postgres.stop();
        }
    }

    @Test
    void publicationOutstandingAcrossARestartIsProjectedByTheNextStartup() {
        // Why: once the source transaction commits, the projection is owed. A
        // process that dies before its listener finishes must not be able to
        // lose that projection permanently, and OrderHub deliberately exposes no
        // privileged endpoint that an operator could call to recover it.
        // Covers: a failed projection leaving a retained publication, that
        // publication surviving a full application shutdown, and a subsequent
        // startup against the same database republishing it, producing exactly
        // one fact with the original operational occurrence time, after which
        // DELETE completion removes the publication.
        // Prevents: an authority-change fact missing indefinitely after a crash
        // or transient projection failure.
        //
        // No resubmission API is called anywhere in this test, so the recovery
        // observed in the second lifecycle can only come from the production
        // startup configuration.

        final UUID tenantId;
        final UUID actorStaffId;
        final UUID targetStaffId;
        final UUID auditEventId = UUID.randomUUID();
        final Instant sourceOccurredAt;

        FailingAnalyticalFactPersistenceConfiguration.FAILING.set(true);

        try (var lifecycleA =
                        start(
                                FailingAnalyticalFactPersistenceConfiguration
                                        .class)) {

            var jdbcTemplate =
                    lifecycleA.getBean(
                            JdbcTemplate.class);

            var privilegedService =
                    lifecycleA.getBean(
                            PrivilegedPositionChangeExecutionService.class);

            var fixture =
                    createFixture(
                            jdbcTemplate);

            tenantId = fixture.tenantId();
            actorStaffId = fixture.actorStaffId();
            targetStaffId = fixture.targetStaffId();

            var decision =
                    privilegedService.execute(
                            appliedCommand(
                                    fixture,
                                    auditEventId));

            assertThat(decision)
                    .as("The source operation must commit even though its"
                            + " projection is going to fail")
                    .isEqualTo(
                            WorkforceMutationDecision.ALLOW);

            assertThat(
                    currentPosition(
                            jdbcTemplate,
                            tenantId,
                            targetStaffId))
                    .as("The operational placement must be committed")
                    .isEqualTo(
                            fixture.managementPositionId());

            assertThat(
                    auditCount(
                            jdbcTemplate,
                            auditEventId))
                    .as("The operational audit evidence must be committed")
                    .isEqualTo(1);

            await().atMost(SETTLE_TIMEOUT)
                    .pollInterval(POLL_INTERVAL)
                    .untilAsserted(() ->
                            assertThat(
                                    publicationStatus(
                                            jdbcTemplate))
                                    .as("The failed projection must leave the"
                                            + " publication retained")
                                    .isEqualTo("FAILED"));

            assertThat(
                    factCount(
                            jdbcTemplate,
                            tenantId,
                            auditEventId))
                    .as("No fact may exist before recovery")
                    .isZero();

            sourceOccurredAt =
                    auditOccurredAt(
                            jdbcTemplate,
                            auditEventId);
        }

        FailingAnalyticalFactPersistenceConfiguration.FAILING.set(false);

        try (var lifecycleB = start()) {

            var jdbcTemplate =
                    lifecycleB.getBean(
                            JdbcTemplate.class);

            assertThat(
                    currentPosition(
                            jdbcTemplate,
                            tenantId,
                            targetStaffId))
                    .as("The committed operational state must be untouched by"
                            + " recovery")
                    .isNotNull();

            assertThat(
                    auditCount(
                            jdbcTemplate,
                            auditEventId))
                    .as("The operational audit evidence must still exist")
                    .isEqualTo(1);

            await().atMost(SETTLE_TIMEOUT)
                    .pollInterval(POLL_INTERVAL)
                    .untilAsserted(() ->
                            assertThat(
                                    factCount(
                                            jdbcTemplate,
                                            tenantId,
                                            auditEventId))
                                    .as("Startup republication must recover the"
                                            + " owed projection without any"
                                            + " operator action")
                                    .isEqualTo(1));

            var fact =
                    jdbcTemplate.queryForMap(
                            """
                            SELECT
                                tenant_id,
                                source_event_id,
                                fact_type,
                                schema_version,
                                actor_subject_key,
                                affected_subject_key,
                                action,
                                outcome,
                                reason_code
                            FROM analytics.workforce_authority_change_facts
                            WHERE tenant_id = ?
                              AND source_event_id = ?
                            """,
                            tenantId,
                            auditEventId);

            assertThat(fact.get("source_event_id"))
                    .isEqualTo(auditEventId);

            assertThat(fact.get("tenant_id"))
                    .isEqualTo(tenantId);

            assertThat(fact.get("action"))
                    .isEqualTo("POSITION_AUTHORITY_CHANGED");

            assertThat(fact.get("outcome"))
                    .isEqualTo("APPLIED");

            assertThat(fact.get("reason_code"))
                    .isNull();

            assertThat(fact.get("fact_type"))
                    .isEqualTo("WORKFORCE_AUTHORITY_CHANGE");

            assertThat(fact.get("schema_version"))
                    .isEqualTo(1);

            assertThat(fact.get("actor_subject_key"))
                    .as("Recovery must not weaken pseudonymity")
                    .isNotNull()
                    .isNotEqualTo(actorStaffId);

            assertThat(fact.get("affected_subject_key"))
                    .isNotNull()
                    .isNotEqualTo(targetStaffId);

            assertThat(
                    factOccurredAt(
                            jdbcTemplate,
                            tenantId,
                            auditEventId))
                    .as("Recovery must carry the operational occurrence time"
                            + " forward rather than substitute the restart"
                            + " time")
                    .isEqualTo(sourceOccurredAt);

            await().atMost(SETTLE_TIMEOUT)
                    .pollInterval(POLL_INTERVAL)
                    .untilAsserted(() ->
                            assertThat(
                                    publicationCount(
                                            jdbcTemplate))
                                    .as("DELETE completion must remove the"
                                            + " recovered publication once it"
                                            + " succeeds")
                                    .isZero());

            assertThat(
                    factCount(
                            jdbcTemplate,
                            tenantId,
                            auditEventId))
                    .as("Recovery must converge on exactly one semantic fact")
                    .isEqualTo(1);
        }
    }

    /**
     * Starts one complete OrderHub application against the shared database.
     *
     * <p>
     * The real {@code application.properties} is in effect, so the registry
     * policies under test are the production ones. Only the datasource, the
     * synthetic Resource Server settings and a random port are supplied.
     * </p>
     */
    private ConfigurableApplicationContext start(
            Class<?>... additionalSources) {

        var sources =
                new Class<?>[additionalSources.length + 1];

        sources[0] = OrderHubApplication.class;

        System.arraycopy(
                additionalSources,
                0,
                sources,
                1,
                additionalSources.length);

        return new SpringApplicationBuilder(sources)
                .properties(
                        "spring.datasource.url=" + postgres.getJdbcUrl(),
                        "spring.datasource.username="
                                + postgres.getUsername(),
                        "spring.datasource.password="
                                + postgres.getPassword(),
                        "orderhub.security.jwt.issuer="
                                + "https://issuer.orderhub.test",
                        "orderhub.security.jwt.audience=orderhub-api-test",
                        "orderhub.security.jwt.jwk-set-uri="
                                + "http://127.0.0.1:1/test-only-jwks",
                        "server.port=0")
                .run();
    }

    private String publicationStatus(
            JdbcTemplate jdbcTemplate) {

        var statuses =
                jdbcTemplate.queryForList(
                        """
                        SELECT status
                        FROM public.event_publication
                        WHERE listener_id = ?
                        """,
                        String.class,
                        ANALYTICS_LISTENER_ID);

        return statuses.size() == 1
                ? statuses.get(0)
                : null;
    }

    private int publicationCount(
            JdbcTemplate jdbcTemplate) {

        return count(
                jdbcTemplate,
                """
                SELECT count(*)
                FROM public.event_publication
                WHERE listener_id = ?
                """,
                ANALYTICS_LISTENER_ID);
    }

    private int factCount(
            JdbcTemplate jdbcTemplate,
            UUID tenantId,
            UUID auditEventId) {

        return count(
                jdbcTemplate,
                """
                SELECT count(*)
                FROM analytics.workforce_authority_change_facts
                WHERE tenant_id = ?
                  AND source_event_id = ?
                """,
                tenantId,
                auditEventId);
    }

    private int auditCount(
            JdbcTemplate jdbcTemplate,
            UUID auditEventId) {

        return count(
                jdbcTemplate,
                """
                SELECT count(*)
                FROM workforce.audit_events
                WHERE audit_event_id = ?
                """,
                auditEventId);
    }

    private int count(
            JdbcTemplate jdbcTemplate,
            String sql,
            Object... arguments) {

        var value =
                jdbcTemplate.queryForObject(
                        sql,
                        Integer.class,
                        arguments);

        return value == null ? 0 : value;
    }

    private Instant auditOccurredAt(
            JdbcTemplate jdbcTemplate,
            UUID auditEventId) {

        return instant(
                jdbcTemplate,
                """
                SELECT occurred_at
                FROM workforce.audit_events
                WHERE audit_event_id = ?
                """,
                auditEventId);
    }

    private Instant factOccurredAt(
            JdbcTemplate jdbcTemplate,
            UUID tenantId,
            UUID auditEventId) {

        return instant(
                jdbcTemplate,
                """
                SELECT occurred_at
                FROM analytics.workforce_authority_change_facts
                WHERE tenant_id = ?
                  AND source_event_id = ?
                """,
                tenantId,
                auditEventId);
    }

    private Instant instant(
            JdbcTemplate jdbcTemplate,
            String sql,
            Object... arguments) {

        var value =
                jdbcTemplate.queryForObject(
                        sql,
                        Timestamp.class,
                        arguments);

        return value == null ? null : value.toInstant();
    }

    private UUID currentPosition(
            JdbcTemplate jdbcTemplate,
            UUID tenantId,
            UUID staffId) {

        return jdbcTemplate.queryForObject(
                """
                SELECT position_id
                FROM workforce.staff_placements
                WHERE tenant_id = ?
                  AND staff_id = ?
                """,
                UUID.class,
                tenantId,
                staffId);
    }

    private PrivilegedPositionChangeCommand appliedCommand(
            Fixture fixture,
            UUID auditEventId) {

        return new PrivilegedPositionChangeCommand(
                fixture.actorStaffId(),
                fixture.targetStaffId(),
                fixture.tenantId(),
                fixture.managementPositionId(),
                true,
                PermissionEnvelope.of(
                        Set.of(
                                PermissionCode.CATALOG_VIEW,
                                PermissionCode.CATALOG_MANAGE)),
                auditEventId,
                UUID.randomUUID());
    }

    private Fixture createFixture(
            JdbcTemplate jdbcTemplate) {

        var tenantId = UUID.randomUUID();
        var departmentId = UUID.randomUUID();

        var actorStaffId = UUID.randomUUID();
        var targetStaffId = UUID.randomUUID();

        var governancePositionId = UUID.randomUUID();
        var operationalPositionId = UUID.randomUUID();
        var managementPositionId = UUID.randomUUID();

        jdbcTemplate.update(
                """
                INSERT INTO workforce.departments (
                    department_id,
                    tenant_id,
                    code,
                    name
                )
                VALUES (?, ?, 'OPS', 'Operations')
                """,
                departmentId,
                tenantId);

        insertPosition(
                jdbcTemplate,
                governancePositionId,
                tenantId,
                "TENANT-GOV",
                "Tenant Governance",
                "TENANT_GOVERNANCE",
                PermissionCode.CATALOG_VIEW,
                PermissionCode.CATALOG_MANAGE);

        insertPosition(
                jdbcTemplate,
                operationalPositionId,
                tenantId,
                "OPS",
                "Operations",
                "OPERATIONAL",
                PermissionCode.CATALOG_VIEW);

        insertPosition(
                jdbcTemplate,
                managementPositionId,
                tenantId,
                "MANAGEMENT",
                "Management",
                "MANAGEMENT",
                PermissionCode.CATALOG_VIEW,
                PermissionCode.CATALOG_MANAGE);

        insertActiveStaff(
                jdbcTemplate,
                actorStaffId,
                tenantId);

        insertActiveStaff(
                jdbcTemplate,
                targetStaffId,
                tenantId);

        insertPlacement(
                jdbcTemplate,
                tenantId,
                actorStaffId,
                departmentId,
                governancePositionId);

        insertPlacement(
                jdbcTemplate,
                tenantId,
                targetStaffId,
                departmentId,
                operationalPositionId);

        return new Fixture(
                tenantId,
                actorStaffId,
                targetStaffId,
                operationalPositionId,
                managementPositionId);
    }

    private void insertPosition(
            JdbcTemplate jdbcTemplate,
            UUID positionId,
            UUID tenantId,
            String code,
            String title,
            String authorityBand,
            PermissionCode... permissions) {

        jdbcTemplate.update(
                """
                INSERT INTO workforce.job_positions (
                    position_id,
                    tenant_id,
                    code,
                    title,
                    authority_band
                )
                VALUES (?, ?, ?, ?, ?)
                """,
                positionId,
                tenantId,
                code,
                title,
                authorityBand);

        for (var permission : permissions) {
            jdbcTemplate.update(
                    """
                    INSERT INTO workforce.job_position_permissions (
                        tenant_id,
                        position_id,
                        permission_code
                    )
                    VALUES (?, ?, ?)
                    """,
                    tenantId,
                    positionId,
                    permission.name());
        }
    }

    private void insertActiveStaff(
            JdbcTemplate jdbcTemplate,
            UUID staffId,
            UUID tenantId) {

        jdbcTemplate.update(
                """
                INSERT INTO workforce.staff_profiles (
                    staff_id,
                    user_id,
                    tenant_id,
                    status
                )
                VALUES (?, ?, ?, 'ACTIVE')
                """,
                staffId,
                UUID.randomUUID(),
                tenantId);
    }

    private void insertPlacement(
            JdbcTemplate jdbcTemplate,
            UUID tenantId,
            UUID staffId,
            UUID departmentId,
            UUID positionId) {

        jdbcTemplate.update(
                """
                INSERT INTO workforce.staff_placements (
                    tenant_id,
                    staff_id,
                    department_id,
                    position_id
                )
                VALUES (?, ?, ?, ?)
                """,
                tenantId,
                staffId,
                departmentId,
                positionId);
    }

    private record Fixture(
            UUID tenantId,
            UUID actorStaffId,
            UUID targetStaffId,
            UUID operationalPositionId,
            UUID managementPositionId) {
    }

    /**
     * Makes analytical fact persistence fail during the first lifecycle only.
     *
     * <p>
     * This is the one condition a running deployment cannot be asked to produce
     * on demand. It is registered only with the first application context, so
     * the recovering context runs entirely on production components.
     * </p>
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class FailingAnalyticalFactPersistenceConfiguration {

        static final AtomicBoolean FAILING =
                new AtomicBoolean();

        @Bean
        @Primary
        WorkforceAuthorityChangeFactRepository failingFactRepository(
                JdbcTemplate jdbcTemplate) {

            var delegate =
                    new PostgreSqlWorkforceAuthorityChangeFactRepository(
                            jdbcTemplate);

            return fact -> {

                if (FAILING.get()) {
                    throw new DeliberateAnalyticalPersistenceFailure();
                }

                delegate.append(fact);
            };
        }
    }

    private static final class DeliberateAnalyticalPersistenceFailure
            extends RuntimeException {

        private static final long serialVersionUID = 1L;
    }
}
