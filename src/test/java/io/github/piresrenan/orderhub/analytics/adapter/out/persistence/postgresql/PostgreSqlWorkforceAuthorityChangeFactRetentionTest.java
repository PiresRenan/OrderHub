package io.github.piresrenan.orderhub.analytics.adapter.out.persistence.postgresql;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import io.github.piresrenan.orderhub.analytics.application.port.out.AnalyticalSubjectPseudonymRepository;
import io.github.piresrenan.orderhub.analytics.application.port.out.WorkforceAuthorityChangeFactRepository;
import io.github.piresrenan.orderhub.analytics.application.port.out.WorkforceAuthorityChangeFactRetentionRepository;
import io.github.piresrenan.orderhub.analytics.application.service.WorkforceAuthorityChangeFactRetentionService;
import io.github.piresrenan.orderhub.analytics.domain.model.AnalyticalFactType;
import io.github.piresrenan.orderhub.analytics.domain.model.AnalyticalRetentionPolicy;
import io.github.piresrenan.orderhub.analytics.domain.model.AnalyticalRetentionPolicyCatalog;
import io.github.piresrenan.orderhub.analytics.domain.model.AnalyticalSubjectKey;
import io.github.piresrenan.orderhub.analytics.domain.model.WorkforceAuthorityChangeAction;
import io.github.piresrenan.orderhub.analytics.domain.model.WorkforceAuthorityChangeFact;
import io.github.piresrenan.orderhub.analytics.domain.model.WorkforceAuthorityChangeOutcome;

@Testcontainers
class PostgreSqlWorkforceAuthorityChangeFactRetentionTest {

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

    private static final UUID TENANT_A =
            UUID.fromString("00000000-0000-4000-8000-00000000a101");

    private static final UUID TENANT_B =
            UUID.fromString("00000000-0000-4000-8000-00000000a102");

    private static final UUID EXPIRED_BEFORE_CUTOFF_EVENT =
            UUID.fromString("00000000-0000-4000-8000-00000000b101");

    private static final UUID EXACT_BOUNDARY_EVENT =
            UUID.fromString("00000000-0000-4000-8000-00000000b102");

    private static final UUID NOT_YET_EXPIRED_EVENT =
            UUID.fromString("00000000-0000-4000-8000-00000000b103");

    private static final UUID OTHER_TENANT_EXPIRED_EVENT =
            UUID.fromString("00000000-0000-4000-8000-00000000b104");

    private static final UUID OPERATIONAL_SUBJECT_ID =
            UUID.fromString("00000000-0000-4000-8000-00000000e101");

    private static final AnalyticalSubjectKey ACTOR_SUBJECT =
            new AnalyticalSubjectKey(
                    UUID.fromString("00000000-0000-4000-8000-00000000c101"));

    private static final AnalyticalSubjectKey AFFECTED_SUBJECT =
            new AnalyticalSubjectKey(
                    UUID.fromString("00000000-0000-4000-8000-00000000d101"));

    private static final String REASON_CODE =
            "AUTHORITY_BAND_RAISED";

    /*
     * Deterministic reference instant, expressed at exact microsecond precision
     * so PostgreSQL TIMESTAMPTZ stores every fixture occurrence time without
     * reduction and the boundary case stays exact.
     */
    private static final Instant REFERENCE_TIME =
            Instant.parse("2026-06-01T12:00:00Z");

    /*
     * Test configuration only.
     *
     * This window exists to make the expiry boundary deterministic. It is NOT
     * the production or legal retention duration for
     * WORKFORCE_AUTHORITY_CHANGE, which remains deliberately unresolved and is
     * supplied by configuration outside the fact schema.
     */
    private static final Duration TEST_RETENTION_WINDOW =
            Duration.ofDays(90);

    /*
     * occurredAt + retentionWindow <= referenceTime is equivalent to
     * occurredAt <= referenceTime - retentionWindow, so this is the inclusive
     * occurrence-time cutoff the purge must apply.
     */
    private static final Instant CUTOFF =
            REFERENCE_TIME.minus(
                    TEST_RETENTION_WINDOW);

    private static JdbcTemplate jdbcTemplate;

    private WorkforceAuthorityChangeFactRepository factRepository;

    private AnalyticalSubjectPseudonymRepository pseudonymRepository;

    private WorkforceAuthorityChangeFactRetentionService retentionService;

    @BeforeAll
    static void migrateAcceptedSchemaChain() {

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
    void resetAnalyticsStorage() {

        // Only analytics-owned relations are reset. Workforce audit evidence is
        // never touched by this test, exactly as retention must never touch it.
        jdbcTemplate.update(
                "DELETE FROM analytics.workforce_authority_change_facts");

        jdbcTemplate.update(
                "DELETE FROM analytics.subject_pseudonyms");

        factRepository =
                new PostgreSqlWorkforceAuthorityChangeFactRepository(
                        jdbcTemplate);

        pseudonymRepository =
                new PostgreSqlAnalyticalSubjectPseudonymRepository(
                        jdbcTemplate);

        WorkforceAuthorityChangeFactRetentionRepository retentionRepository =
                new PostgreSqlWorkforceAuthorityChangeFactRetentionRepository(
                        jdbcTemplate);

        retentionService =
                new WorkforceAuthorityChangeFactRetentionService(
                        testRetentionPolicyCatalog(),
                        retentionRepository);
    }

    @Test
    void deletesOnlyExpiredFactsForTheTenantAtTheInclusivePolicyBoundary() {
        // Why: retention is only meaningful if analytics can actually remove
        // its own expired derivatives, and only safe if removal is bounded by
        // Tenant and by the effective policy boundary. A purge that used a
        // strict comparison would retain a fact past its own expiry, and one
        // that ignored Tenant scope would delete another Tenant's analytical
        // history in a single statement.
        // Covers: inclusive expiry at exactly the derived cutoff, deletion of
        // facts older than the cutoff, preservation of not-yet-expired facts,
        // preservation of another Tenant's expired facts, preservation of the
        // more-sensitive subject mapping, the deleted-row result, and
        // sequential idempotency of a repeated purge.
        // Prevents: an off-by-one expiry boundary, cross-Tenant deletion,
        // implicit mapping deletion, mutation of surviving facts, and a purge
        // that reports work it did not do.
        //
        // The cutoff is derived from the same algebra the service must apply,
        // so a service that failed to subtract the retention window, or that
        // compared strictly, would leave the wrong rows behind.

        appendFact(
                TENANT_A,
                EXPIRED_BEFORE_CUTOFF_EVENT,
                CUTOFF.minus(
                        Duration.ofSeconds(1)));

        appendFact(
                TENANT_A,
                EXACT_BOUNDARY_EVENT,
                CUTOFF);

        appendFact(
                TENANT_A,
                NOT_YET_EXPIRED_EVENT,
                CUTOFF.plus(
                        Duration.ofSeconds(1)));

        appendFact(
                TENANT_B,
                OTHER_TENANT_EXPIRED_EVENT,
                CUTOFF.minus(
                        Duration.ofDays(1)));

        var establishedSubjectKey =
                pseudonymRepository.resolveOrCreate(
                        TENANT_A,
                        OPERATIONAL_SUBJECT_ID);

        assertThat(totalFactRowCount())
                .as("All four analytical facts must be seeded through the"
                        + " accepted append contract before retention runs")
                .isEqualTo(4);

        assertThat(subjectMappingRowCount())
                .as("One subject mapping must exist before retention runs")
                .isEqualTo(1);

        var survivingTenantAFact =
                persistedFact(
                        TENANT_A,
                        NOT_YET_EXPIRED_EVENT);

        var survivingTenantBFact =
                persistedFact(
                        TENANT_B,
                        OTHER_TENANT_EXPIRED_EVENT);

        var deleted =
                retentionService.purgeExpired(
                        TENANT_A,
                        REFERENCE_TIME);

        assertThat(deleted)
                .as("The purge must report exactly the two expired Tenant A"
                        + " facts it removed")
                .isEqualTo(2);

        assertThat(
                factExists(
                        TENANT_A,
                        EXPIRED_BEFORE_CUTOFF_EVENT))
                .as("A fact whose occurrence time precedes the cutoff must be"
                        + " deleted")
                .isFalse();

        assertThat(
                factExists(
                        TENANT_A,
                        EXACT_BOUNDARY_EVENT))
                .as("A fact expiring exactly at the reference time must be"
                        + " deleted, because expiry is inclusive")
                .isFalse();

        assertThat(
                factExists(
                        TENANT_A,
                        NOT_YET_EXPIRED_EVENT))
                .as("A fact that has not reached expiry must be preserved")
                .isTrue();

        assertThat(
                factExists(
                        TENANT_B,
                        OTHER_TENANT_EXPIRED_EVENT))
                .as("Another Tenant's expired fact must never be deleted by"
                        + " this Tenant's retention")
                .isTrue();

        assertThat(totalFactRowCount())
                .as("Exactly the two expired Tenant A facts may leave storage")
                .isEqualTo(2);

        assertThat(
                persistedFact(
                        TENANT_A,
                        NOT_YET_EXPIRED_EVENT))
                .as("Retention may delete expired facts but must never mutate a"
                        + " surviving fact")
                .isEqualTo(survivingTenantAFact);

        assertThat(
                persistedFact(
                        TENANT_B,
                        OTHER_TENANT_EXPIRED_EVENT))
                .as("Another Tenant's fact must survive unchanged")
                .isEqualTo(survivingTenantBFact);

        assertThat(subjectMappingRowCount())
                .as("Analytical fact retention must not implicitly delete the"
                        + " more-sensitive subject mapping")
                .isEqualTo(1);

        assertThat(
                persistedAnalyticalSubjectKey(
                        TENANT_A,
                        OPERATIONAL_SUBJECT_ID))
                .as("The persisted analytical identity must be unchanged by"
                        + " fact retention")
                .isEqualTo(
                        establishedSubjectKey.value());

        var secondPurgeDeleted =
                retentionService.purgeExpired(
                        TENANT_A,
                        REFERENCE_TIME);

        assertThat(secondPurgeDeleted)
                .as("Repeating the same purge must delete nothing further")
                .isEqualTo(0);

        assertThat(totalFactRowCount())
                .as("A repeated purge must leave the surviving facts intact")
                .isEqualTo(2);

        assertThat(
                persistedFact(
                        TENANT_A,
                        NOT_YET_EXPIRED_EVENT))
                .as("A repeated purge must not mutate the surviving Tenant A"
                        + " fact")
                .isEqualTo(survivingTenantAFact);

        assertThat(
                persistedFact(
                        TENANT_B,
                        OTHER_TENANT_EXPIRED_EVENT))
                .as("A repeated purge must not mutate the other Tenant's fact")
                .isEqualTo(survivingTenantBFact);

        assertThat(subjectMappingRowCount())
                .as("A repeated purge must not affect the subject mapping")
                .isEqualTo(1);
    }

    @Test
    void concurrentPurgesDeleteEachExpiredFactExactlyOnce() throws Exception {
        // Why: retention is expected to run from more than one place over time,
        // and two workers evaluating the same Tenant and cutoff must not delete
        // a fact twice, report work another worker did, or need an application
        // lock to stay correct. Correctness here has to be PostgreSQL's, not
        // the JVM's.
        // Covers: two genuinely distinct PostgreSQL backends purging the same
        // Tenant and cutoff while one holds uncommitted row locks, the deleted
        // counts they each report, and the resulting stored state.
        // Prevents: double deletion, double counting, deletion of non-expired
        // or cross-Tenant facts, and any future need for a JVM lock, advisory
        // lock, SKIP LOCKED or escalated isolation.
        //
        // Overlap is never assumed from timing. Worker B is proven to be
        // blocked by worker A through PostgreSQL's own lock graph before A
        // commits, so a run that failed to overlap fails the test rather than
        // passing silently.

        seedRetentionFixture();

        assertThat(
                expiredFactRowCount(
                        TENANT_A))
                .as("Two Tenant A facts must be expired at the cutoff before"
                        + " the concurrent phase")
                .isEqualTo(2);

        assertThat(
                factExists(
                        TENANT_A,
                        NOT_YET_EXPIRED_EVENT))
                .as("One Tenant A fact must not be expired yet")
                .isTrue();

        assertThat(
                expiredFactRowCount(
                        TENANT_B))
                .as("One Tenant B fact must be expired at the cutoff")
                .isEqualTo(1);

        var survivingTenantAFact =
                persistedFact(
                        TENANT_A,
                        NOT_YET_EXPIRED_EVENT);

        var survivingTenantBFact =
                persistedFact(
                        TENANT_B,
                        OTHER_TENANT_EXPIRED_EVENT);

        var executor =
                Executors.newSingleThreadExecutor();

        Connection connectionA = null;
        Connection connectionB = null;
        Future<Integer> workerB = null;

        try {
            connectionA = dedicatedConnection();
            connectionB = dedicatedConnection();

            assertThat(connectionA.getAutoCommit())
                    .as("Worker A must own its transaction boundary")
                    .isFalse();

            assertThat(connectionB.getAutoCommit())
                    .as("Worker B must own its transaction boundary")
                    .isFalse();

            assertThat(connectionA.getTransactionIsolation())
                    .as("Worker A must run at the project's ordinary READ"
                            + " COMMITTED isolation, not an escalated one")
                    .isEqualTo(
                            Connection.TRANSACTION_READ_COMMITTED);

            assertThat(connectionB.getTransactionIsolation())
                    .as("Worker B must run at the project's ordinary READ"
                            + " COMMITTED isolation, not an escalated one")
                    .isEqualTo(
                            Connection.TRANSACTION_READ_COMMITTED);

            var backendPidA =
                    backendPid(
                            connectionA);

            var backendPidB =
                    backendPid(
                            connectionB);

            assertThat(backendPidA)
                    .as("The two workers must be distinct PostgreSQL backends,"
                            + " not two objects sharing one session")
                    .isNotEqualTo(backendPidB);

            var retentionServiceA =
                    retentionServiceOn(
                            connectionA);

            var retentionServiceB =
                    retentionServiceOn(
                            connectionB);

            var deletedByA =
                    retentionServiceA.purgeExpired(
                            TENANT_A,
                            REFERENCE_TIME);

            assertThat(deletedByA)
                    .as("Worker A must claim both expired Tenant A facts")
                    .isEqualTo(2);

            // Worker A deliberately stays uncommitted here, so it still holds
            // the row locks worker B has to contend with.
            var workerBStarted =
                    new CountDownLatch(1);

            final var contendingService = retentionServiceB;

            workerB =
                    executor.submit(() -> {

                        workerBStarted.countDown();

                        return contendingService.purgeExpired(
                                TENANT_A,
                                REFERENCE_TIME);
                    });

            assertThat(
                    workerBStarted.await(
                            10,
                            TimeUnit.SECONDS))
                    .as("Worker B must actually start before overlap is"
                            + " evaluated")
                    .isTrue();

            var blockedByA =
                    awaitBlockedBy(
                            backendPidB,
                            backendPidA);

            assertThat(blockedByA)
                    .as("PostgreSQL must report worker B as blocked by worker"
                            + " A, so the overlap is proven rather than assumed")
                    .isTrue();

            var observedWaitEventType =
                    waitEventType(
                            backendPidB);

            assertThat(observedWaitEventType)
                    .as("A worker blocked on another worker's uncommitted"
                            + " deletion must be waiting on a lock")
                    .isEqualTo("Lock");

            assertThat(workerB.isDone())
                    .as("Worker B must still be incomplete while blocked")
                    .isFalse();

            connectionA.commit();

            var deletedByB =
                    workerB.get(
                            30,
                            TimeUnit.SECONDS);

            assertThat(deletedByB)
                    .as("Once worker A's deletion commits, worker B must find"
                            + " nothing left to delete and must not re-count it")
                    .isEqualTo(0);

            connectionB.commit();

            assertThat(deletedByA + deletedByB)
                    .as("Every expired fact must be accounted for exactly once"
                            + " across both workers")
                    .isEqualTo(2);

        } finally {
            // Release worker A first: while it holds uncommitted deletions,
            // worker B stays blocked inside PostgreSQL and would otherwise
            // leave the build waiting on a connection that is never resolved.
            rollbackQuietly(
                    connectionA);

            awaitQuietly(
                    workerB);

            rollbackQuietly(
                    connectionB);

            closeQuietly(
                    connectionA);

            closeQuietly(
                    connectionB);

            executor.shutdownNow();
        }

        assertThat(
                factExists(
                        TENANT_A,
                        EXPIRED_BEFORE_CUTOFF_EVENT))
                .as("The fact expired before the cutoff must be gone exactly"
                        + " once")
                .isFalse();

        assertThat(
                factExists(
                        TENANT_A,
                        EXACT_BOUNDARY_EVENT))
                .as("The fact expiring exactly at the boundary must be gone"
                        + " exactly once")
                .isFalse();

        assertThat(
                expiredFactRowCount(
                        TENANT_A))
                .as("No expired Tenant A fact may remain after concurrent"
                        + " purges")
                .isEqualTo(0);

        assertThat(
                factExists(
                        TENANT_A,
                        NOT_YET_EXPIRED_EVENT))
                .as("Concurrent purges must not remove a fact that has not"
                        + " expired")
                .isTrue();

        assertThat(
                factExists(
                        TENANT_B,
                        OTHER_TENANT_EXPIRED_EVENT))
                .as("Concurrent purges scoped to one Tenant must not remove"
                        + " another Tenant's expired fact")
                .isTrue();

        assertThat(totalFactRowCount())
                .as("Exactly the two expired Tenant A facts may leave storage")
                .isEqualTo(2);

        assertThat(
                persistedFact(
                        TENANT_A,
                        NOT_YET_EXPIRED_EVENT))
                .as("A surviving fact must be unchanged by concurrent purges")
                .isEqualTo(survivingTenantAFact);

        assertThat(
                persistedFact(
                        TENANT_B,
                        OTHER_TENANT_EXPIRED_EVENT))
                .as("Another Tenant's fact must be unchanged by concurrent"
                        + " purges")
                .isEqualTo(survivingTenantBFact);

        assertThat(subjectMappingRowCount())
                .as("Concurrent fact retention must not affect the subject"
                        + " mapping")
                .isEqualTo(1);
    }

    /**
     * Seeds the shared retention fixture through the accepted contracts, so
     * both retention tests operate on rows the production path produces.
     */
    private void seedRetentionFixture() {

        appendFact(
                TENANT_A,
                EXPIRED_BEFORE_CUTOFF_EVENT,
                CUTOFF.minus(
                        Duration.ofSeconds(1)));

        appendFact(
                TENANT_A,
                EXACT_BOUNDARY_EVENT,
                CUTOFF);

        appendFact(
                TENANT_A,
                NOT_YET_EXPIRED_EVENT,
                CUTOFF.plus(
                        Duration.ofSeconds(1)));

        appendFact(
                TENANT_B,
                OTHER_TENANT_EXPIRED_EVENT,
                CUTOFF.minus(
                        Duration.ofDays(1)));

        pseudonymRepository.resolveOrCreate(
                TENANT_A,
                OPERATIONAL_SUBJECT_ID);
    }

    /**
     * Opens one dedicated physical PostgreSQL session that owns its own
     * transaction, so a worker can hold uncommitted row locks.
     */
    private static Connection dedicatedConnection()
            throws SQLException {

        var connection =
                DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(),
                        POSTGRES.getUsername(),
                        POSTGRES.getPassword());

        connection.setAutoCommit(false);

        return connection;
    }

    /**
     * Builds the production retention service over one dedicated session.
     *
     * <p>
     * The connection is wrapped with close suppression so the production
     * adapter can be used unchanged: it still owns no transaction boundary and
     * simply participates in the transaction this test established.
     * </p>
     */
    private static WorkforceAuthorityChangeFactRetentionService retentionServiceOn(
            Connection connection) {

        var dataSource =
                new SingleConnectionDataSource(
                        connection,
                        true);

        WorkforceAuthorityChangeFactRetentionRepository retentionRepository =
                new PostgreSqlWorkforceAuthorityChangeFactRetentionRepository(
                        new JdbcTemplate(
                                dataSource));

        return new WorkforceAuthorityChangeFactRetentionService(
                testRetentionPolicyCatalog(),
                retentionRepository);
    }

    private static int backendPid(
            Connection connection)
            throws SQLException {

        try (var statement =
                        connection.createStatement();
                var resultSet =
                        statement.executeQuery(
                                "SELECT pg_backend_pid()")) {

            resultSet.next();

            return resultSet.getInt(1);
        }
    }

    /**
     * Waits until PostgreSQL itself reports the lock relationship.
     *
     * <p>
     * The poll is observational: it reads PostgreSQL's lock graph rather than
     * synchronizing the workers, and it is bounded so a run that never
     * overlapped fails instead of hanging.
     * </p>
     */
    private static boolean awaitBlockedBy(
            int blockedPid,
            int blockingPid)
            throws InterruptedException {

        var deadline =
                System.nanoTime()
                        + Duration.ofSeconds(20).toNanos();

        while (System.nanoTime() < deadline) {

            if (isBlockedBy(
                    blockedPid,
                    blockingPid)) {

                return true;
            }

            Thread.sleep(50);
        }

        return false;
    }

    private static boolean isBlockedBy(
            int blockedPid,
            int blockingPid) {

        return Boolean.TRUE.equals(
                jdbcTemplate.queryForObject(
                        """
                        SELECT EXISTS (
                            SELECT 1
                            FROM unnest(pg_blocking_pids(?)) AS blocker(pid)
                            WHERE blocker.pid = ?
                        )
                        """,
                        Boolean.class,
                        blockedPid,
                        blockingPid));
    }

    private static String waitEventType(
            int backendPid) {

        var waitEventTypes =
                jdbcTemplate.queryForList(
                        """
                        SELECT wait_event_type
                        FROM pg_stat_activity
                        WHERE pid = ?
                        """,
                        String.class,
                        backendPid);

        return waitEventTypes.isEmpty()
                ? null
                : waitEventTypes.get(0);
    }

    private static void rollbackQuietly(
            Connection connection) {

        if (connection == null) {
            return;
        }

        try {
            connection.rollback();

        } catch (SQLException ignored) {
            // Cleanup must never mask the assertion that actually failed.
        }
    }

    private static void awaitQuietly(
            Future<Integer> worker) {

        if (worker == null) {
            return;
        }

        try {
            worker.get(
                    30,
                    TimeUnit.SECONDS);

        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();

        } catch (Exception ignored) {
            // Cleanup must never mask the assertion that actually failed.
        }
    }

    private static void closeQuietly(
            Connection connection) {

        if (connection == null) {
            return;
        }

        try {
            connection.close();

        } catch (SQLException ignored) {
            // Cleanup must never mask the assertion that actually failed.
        }
    }

    private static int expiredFactRowCount(
            UUID tenantId) {

        return jdbcTemplate.queryForObject(
                """
                SELECT count(*)
                FROM analytics.workforce_authority_change_facts
                WHERE tenant_id = ?
                  AND fact_type = ?
                  AND occurred_at <= ?
                """,
                Integer.class,
                tenantId,
                AnalyticalFactType.WORKFORCE_AUTHORITY_CHANGE.name(),
                Timestamp.from(
                        CUTOFF));
    }

    /**
     * Builds the retention configuration used by this test only.
     *
     * <p>
     * The window is a deterministic test value. The production and legal
     * retention duration for {@code WORKFORCE_AUTHORITY_CHANGE} remains
     * unresolved and is deliberately not asserted here.
     * </p>
     */
    private static AnalyticalRetentionPolicyCatalog testRetentionPolicyCatalog() {

        return new AnalyticalRetentionPolicyCatalog(
                Map.of(
                        AnalyticalFactType.WORKFORCE_AUTHORITY_CHANGE,
                        new AnalyticalRetentionPolicy(
                                TEST_RETENTION_WINDOW)));
    }

    /**
     * Seeds one analytical fact through the accepted append contract rather
     * than through raw SQL, so retention operates on rows the production path
     * actually produces.
     */
    private void appendFact(
            UUID tenantId,
            UUID sourceEventId,
            Instant occurredAt) {

        factRepository.append(
                new WorkforceAuthorityChangeFact(
                        sourceEventId,
                        tenantId,
                        ACTOR_SUBJECT,
                        AFFECTED_SUBJECT,
                        WorkforceAuthorityChangeAction.POSITION_AUTHORITY_CHANGED,
                        WorkforceAuthorityChangeOutcome.APPLIED,
                        REASON_CODE,
                        occurredAt));
    }

    private static boolean factExists(
            UUID tenantId,
            UUID sourceEventId) {

        return factRowCount(
                tenantId,
                sourceEventId) == 1;
    }

    private static int factRowCount(
            UUID tenantId,
            UUID sourceEventId) {

        return jdbcTemplate.queryForObject(
                """
                SELECT count(*)
                FROM analytics.workforce_authority_change_facts
                WHERE tenant_id = ?
                  AND source_event_id = ?
                  AND fact_type = ?
                """,
                Integer.class,
                tenantId,
                sourceEventId,
                AnalyticalFactType.WORKFORCE_AUTHORITY_CHANGE.name());
    }

    private static int totalFactRowCount() {

        return jdbcTemplate.queryForObject(
                """
                SELECT count(*)
                FROM analytics.workforce_authority_change_facts
                """,
                Integer.class);
    }

    private static int subjectMappingRowCount() {

        return jdbcTemplate.queryForObject(
                """
                SELECT count(*)
                FROM analytics.subject_pseudonyms
                """,
                Integer.class);
    }

    private static UUID persistedAnalyticalSubjectKey(
            UUID tenantId,
            UUID operationalSubjectId) {

        return jdbcTemplate.queryForObject(
                """
                SELECT analytical_subject_key
                FROM analytics.subject_pseudonyms
                WHERE tenant_id = ?
                  AND operational_subject_id = ?
                """,
                UUID.class,
                tenantId,
                operationalSubjectId);
    }

    private static PersistedFact persistedFact(
            UUID tenantId,
            UUID sourceEventId) {

        return jdbcTemplate.queryForObject(
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
                    reason_code,
                    occurred_at
                FROM analytics.workforce_authority_change_facts
                WHERE tenant_id = ?
                  AND source_event_id = ?
                  AND fact_type = ?
                """,
                (resultSet, rowNumber) ->
                        mapPersistedFact(
                                resultSet),
                tenantId,
                sourceEventId,
                AnalyticalFactType.WORKFORCE_AUTHORITY_CHANGE.name());
    }

    private static PersistedFact mapPersistedFact(
            ResultSet resultSet)
            throws SQLException {

        return new PersistedFact(
                resultSet.getObject(
                        "tenant_id",
                        UUID.class),
                resultSet.getObject(
                        "source_event_id",
                        UUID.class),
                resultSet.getString(
                        "fact_type"),
                resultSet.getInt(
                        "schema_version"),
                resultSet.getObject(
                        "actor_subject_key",
                        UUID.class),
                resultSet.getObject(
                        "affected_subject_key",
                        UUID.class),
                resultSet.getString(
                        "action"),
                resultSet.getString(
                        "outcome"),
                resultSet.getString(
                        "reason_code"),
                resultSet.getTimestamp(
                                "occurred_at")
                        .toInstant());
    }

    private record PersistedFact(
            UUID tenantId,
            UUID sourceEventId,
            String factType,
            int schemaVersion,
            UUID actorSubjectKey,
            UUID affectedSubjectKey,
            String action,
            String outcome,
            String reasonCode,
            Instant occurredAt) {
    }
}
