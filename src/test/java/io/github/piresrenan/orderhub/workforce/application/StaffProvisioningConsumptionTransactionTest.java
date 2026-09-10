package io.github.piresrenan.orderhub.workforce.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import io.github.piresrenan.orderhub.users.UsersConfiguration;
import io.github.piresrenan.orderhub.users.application.port.in.*;
import io.github.piresrenan.orderhub.workforce.adapter.out.persistence.postgresql.PostgreSqlStaffMaterializationRepository;
import io.github.piresrenan.orderhub.workforce.adapter.out.persistence.postgresql.PostgreSqlStaffProvisioningIntentRepository;
import io.github.piresrenan.orderhub.workforce.adapter.out.persistence.postgresql.PostgreSqlStaffProvisioningEvidenceRepository;
import io.github.piresrenan.orderhub.workforce.adapter.out.transaction.spring.SpringWorkforceTransactionExecutor;
import io.github.piresrenan.orderhub.workforce.application.model.*;
import io.github.piresrenan.orderhub.workforce.application.port.out.StaffProvisioningCompletion;
import io.github.piresrenan.orderhub.workforce.application.service.StaffProvisioningConsumptionService;
import io.github.piresrenan.orderhub.workforce.application.service.StaffProvisioningIssuanceService;
import io.github.piresrenan.orderhub.workforce.application.service.StaffProvisioningUnavailableException;
import io.github.piresrenan.orderhub.workforce.application.port.out.StaffMaterializationConflictException;

/**
 * Why: a successful inner Users scope must not commit ahead of provisioning.
 * Covers: real intent/User/binding/membership/Staff rollback and successful retry.
 * Prevents: partial durable identity effects and a spent proof after downstream failure.
 * Authorization is an explicit synthetic collaborator here; this suite certifies
 * transaction propagation, not production delegation or role-assignment semantics.
 */
@Testcontainers
class StaffProvisioningConsumptionTransactionTest {
    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("postgres:18.6-trixie@sha256:4ef4dbc939d61acea57712655ddb4b4ab27419c913f94cca0cd57cb3ea3c2280")
                    .asCompatibleSubstituteFor("postgres"));
    private static AnnotationConfigApplicationContext context;
    private static JdbcTemplate jdbc;
    private static TransactionTemplate transaction;
    private static ResolveOrCreateExternalUserUseCase users;
    private static EnsureActiveTenantMembershipUseCase memberships;

    @BeforeAll
    static void compose() {
        var source = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(source).load().migrate();
        jdbc = new JdbcTemplate(source);
        var manager = new DataSourceTransactionManager(source);
        transaction = new TransactionTemplate(manager);
        transaction.setTimeout(10);
        context = new AnnotationConfigApplicationContext();
        context.registerBean(JdbcTemplate.class, () -> jdbc);
        context.registerBean(PlatformTransactionManager.class, () -> manager);
        context.register(UsersConfiguration.class);
        context.refresh();
        users = context.getBean(ResolveOrCreateExternalUserUseCase.class);
        memberships = context.getBean(EnsureActiveTenantMembershipUseCase.class);
    }

    @AfterAll
    static void closeContext() {
        if (context != null) { context.close(); }
    }

    @Test
    void downstreamCompletionFailureRollsBackAllJoinedMutationsThenRetrySucceeds() {
        var fixture = fixture();
        var failing = service(new SyntheticCompletion(true), users, memberships);
        assertThatThrownBy(() -> consume(failing, fixture)).isInstanceOf(IllegalStateException.class);
        assertRolledBack(fixture);
        var staffId = consume(service(new SyntheticCompletion(false), users, memberships), fixture);
        assertThat(staffId).isNotNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM workforce.staff_profiles WHERE tenant_id = ?",
                Integer.class, fixture.tenant())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users.tenant_memberships WHERE tenant_id = ?",
                Integer.class, fixture.tenant())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM workforce.provisioning_events WHERE intent_id = ? AND action = 'CONSUMED'",
                Integer.class, fixture.issued().intentId())).isEqualTo(1);
    }

    @Test
    void outerRollbackAlsoUndoesSuccessfullyReturnedProvisioning() {
        var fixture = fixture();
        transaction.execute(status -> {
            assertThat(consume(service(new SyntheticCompletion(false), users, memberships), fixture)).isNotNull();
            status.setRollbackOnly();
            return null;
        });
        assertRolledBack(fixture);
    }

    @Test
    void usersFailureAfterRealCreationRollsBackItsOwnSuccessfulInnerScope() {
        var fixture = fixture();
        ResolveOrCreateExternalUserUseCase failing = query -> {
            users.resolveOrCreate(query);
            throw new IllegalStateException("Synthetic Users boundary failure");
        };
        assertThatThrownBy(() -> consume(service(new SyntheticCompletion(false), failing, memberships), fixture))
                .isInstanceOf(IllegalStateException.class);
        assertRolledBack(fixture);
    }

    @Test
    void nonOperationalMembershipLeavesIntentPendingAndHistoricalIdentityIntact() {
        var fixture = fixture();
        var user = users.resolveOrCreate(new ResolveExternalIdentityQuery("https://synthetic.test", fixture.subject()));
        jdbc.update("INSERT INTO users.tenant_memberships (user_id, tenant_id, status) VALUES (?, ?, 'TERMINATED')",
                user.userId(), fixture.tenant());
        assertThatThrownBy(() -> consume(service(new SyntheticCompletion(false), users, memberships), fixture))
                .hasMessage("Staff provisioning is unavailable");
        assertThat(jdbc.queryForObject("SELECT consumed_at IS NULL FROM workforce.staff_provisioning_intents WHERE intent_id = ?",
                Boolean.class, fixture.issued().intentId())).isTrue();
        assertThat(jdbc.queryForObject("SELECT status FROM users.tenant_memberships WHERE tenant_id = ?",
                String.class, fixture.tenant())).isEqualTo("TERMINATED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM workforce.staff_profiles WHERE tenant_id = ?",
                Integer.class, fixture.tenant())).isZero();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void identityLockRemainsHeldUntilOuterCommitOrRollback(boolean rollback) throws Exception {
        var fixture = fixture();
        var ownerReady = new CountDownLatch(1);
        var releaseOwner = new CountDownLatch(1);
        var waiterPid = new ArrayBlockingQueue<Integer>(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> transaction.execute(status -> {
                consume(service(new SyntheticCompletion(false), users, memberships), fixture);
                ownerReady.countDown();
                try {
                    if (!releaseOwner.await(8, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Synthetic outer transaction release timed out");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
                if (rollback) { status.setRollbackOnly(); }
                return null;
            }));
            assertThat(ownerReady.await(8, TimeUnit.SECONDS)).isTrue();
            var second = executor.submit(() -> transaction.execute(status -> {
                waiterPid.add(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                return users.resolveOrCreate(new ResolveExternalIdentityQuery("https://synthetic.test", fixture.subject()));
            }));
            var pid = waiterPid.poll(5, TimeUnit.SECONDS);
            assertThat(pid).isNotNull();
            org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                    assertThat(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM pg_locks WHERE pid = ? AND locktype = 'advisory' AND NOT granted)",
                            Boolean.class, pid)).isTrue());
            assertThat(second.isDone()).isFalse();
            releaseOwner.countDown();
            first.get(12, TimeUnit.SECONDS);
            var resolved = second.get(12, TimeUnit.SECONDS);
            assertThat(resolved.userId()).isNotNull();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM users.external_identity_bindings WHERE subject = ?",
                    Integer.class, fixture.subject())).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM users.users u WHERE NOT EXISTS (SELECT 1 FROM users.external_identity_bindings b WHERE b.user_id = u.id)",
                    Integer.class)).isZero();
        } finally {
            releaseOwner.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(20, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void inconsistentExistingStaffRollsBackNewMembershipAndConsumption() {
        var fixture = fixture();
        var user = users.resolveOrCreate(new ResolveExternalIdentityQuery("https://synthetic.test", fixture.subject()));
        jdbc.update("INSERT INTO workforce.staff_profiles VALUES (?, ?, ?, 'INACTIVE')",
                UUID.randomUUID(), user.userId(), fixture.tenant());
        assertThatThrownBy(() -> consume(service(new SyntheticCompletion(false), users, memberships), fixture))
                .isInstanceOf(StaffMaterializationConflictException.class);
        assertThat(jdbc.queryForObject("SELECT consumed_at IS NULL FROM workforce.staff_provisioning_intents WHERE intent_id = ?",
                Boolean.class, fixture.issued().intentId())).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users.tenant_memberships WHERE tenant_id = ?",
                Integer.class, fixture.tenant())).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM workforce.staff_profiles WHERE tenant_id = ?",
                String.class, fixture.tenant())).isEqualTo("INACTIVE");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void competingConsumptionOrCancellationHasOneTerminalEffect(boolean cancellation) throws Exception {
        var executor = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 32; round++) {
                var fixture = fixture();
                var ready = new CountDownLatch(2);
                var start = new CountDownLatch(1);
                java.util.concurrent.Callable<Boolean> consumer = () -> {
                    ready.countDown();
                    assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                    try {
                        consume(service(new SyntheticCompletion(false), users, memberships), fixture);
                        return true;
                    } catch (StaffProvisioningUnavailableException exception) {
                        return false;
                    }
                };
                java.util.concurrent.Callable<Boolean> competitor = cancellation ? () -> {
                    ready.countDown();
                    assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                    return new PostgreSqlStaffProvisioningIntentRepository(jdbc).cancelPending(
                            fixture.tenant(), fixture.issued().intentId(), OffsetDateTime.now());
                } : consumer;
                var first = executor.submit(consumer);
                var second = executor.submit(competitor);
                assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
                start.countDown();
                var consumed = first.get(15, TimeUnit.SECONDS);
                var otherWon = second.get(15, TimeUnit.SECONDS);
                assertThat((consumed ? 1 : 0) + (otherWon ? 1 : 0)).isEqualTo(1);
                assertThat(jdbc.queryForObject("SELECT count(*) FROM workforce.staff_profiles WHERE tenant_id = ?",
                        Integer.class, fixture.tenant())).isEqualTo(cancellation && otherWon ? 0 : 1);
                assertThat(jdbc.queryForObject("SELECT (consumed_at IS NOT NULL) <> (cancelled_at IS NOT NULL) FROM workforce.staff_provisioning_intents WHERE intent_id = ?",
                        Boolean.class, fixture.issued().intentId())).isTrue();
            }
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(20, TimeUnit.SECONDS)).isTrue();
        }
    }

    private StaffProvisioningConsumptionService service(StaffProvisioningCompletion completion,
            ResolveOrCreateExternalUserUseCase identity, EnsureActiveTenantMembershipUseCase membership) {
        return new StaffProvisioningConsumptionService(new PostgreSqlStaffProvisioningIntentRepository(jdbc),
                identity, membership, new PostgreSqlStaffMaterializationRepository(jdbc), completion,
                new SpringWorkforceTransactionExecutor(transaction), Clock.systemUTC());
    }

    private UUID consume(StaffProvisioningConsumptionService service, Fixture fixture) {
        return service.consume(fixture.issued().credential(), "https://synthetic.test", fixture.subject());
    }

    private void assertRolledBack(Fixture fixture) {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM workforce.provisioning_events WHERE intent_id = ?",
                Integer.class, fixture.issued().intentId())).isZero();
        assertThat(jdbc.queryForObject("SELECT consumed_at IS NULL FROM workforce.staff_provisioning_intents WHERE intent_id = ?",
                Boolean.class, fixture.issued().intentId())).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users.external_identity_bindings WHERE subject = ?",
                Integer.class, fixture.subject())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users.tenant_memberships WHERE tenant_id = ?",
                Integer.class, fixture.tenant())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM workforce.staff_profiles WHERE tenant_id = ?",
                Integer.class, fixture.tenant())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users.users u WHERE NOT EXISTS (SELECT 1 FROM users.external_identity_bindings b WHERE b.user_id = u.id)",
                Integer.class)).isZero();
    }

    private Fixture fixture() {
        var tenant = UUID.randomUUID();
        var department = UUID.randomUUID();
        var position = UUID.randomUUID();
        jdbc.update("INSERT INTO workforce.departments VALUES (?, ?, 'STAFF', 'Synthetic department')", department, tenant);
        jdbc.update("INSERT INTO workforce.job_positions VALUES (?, ?, 'STAFF', 'Synthetic position', 'OPERATIONAL')", position, tenant);
        var issuance = new StaffProvisioningIssuanceService(new PostgreSqlStaffProvisioningIntentRepository(jdbc),
                Clock.systemUTC(), Duration.ofHours(1), new SecureRandom());
        var issued = (StaffProvisioningIssuance.Issued) issuance.issue(new IssueStaffProvisioningIntentCommand(
                tenant, UUID.randomUUID(), department, position, null, UUID.randomUUID(), UUID.randomUUID()));
        return new Fixture(tenant, UUID.randomUUID().toString(), issued);
    }

    private record Fixture(UUID tenant, String subject, StaffProvisioningIssuance.Issued issued) {}

    private record SyntheticCompletion(boolean fail) implements StaffProvisioningCompletion {
        @Override public void authorize(ConsumedStaffProvisioningIntent intent) {}
        @Override public void complete(ConsumedStaffProvisioningIntent intent, UUID userId, UUID staffId) {
            new PostgreSqlStaffProvisioningEvidenceRepository(jdbc).append(
                    new StaffProvisioningEvidence(intent.tenantId(), intent.intentId(), intent.issuedByUserId(),
                            userId, staffId, StaffProvisioningEvidence.Action.CONSUMED, intent.correlationId()));
            if (fail) { throw new IllegalStateException("Synthetic downstream completion failure"); }
        }
    }
}
