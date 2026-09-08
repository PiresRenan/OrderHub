package io.github.piresrenan.orderhub.inventory.adapter.out.persistence.postgresql;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import io.github.piresrenan.orderhub.inventory.adapter.out.transaction.spring.SpringInventoryAdministrationTransactionExecutor;
import io.github.piresrenan.orderhub.inventory.application.port.in.*;
import io.github.piresrenan.orderhub.inventory.application.port.out.*;
import io.github.piresrenan.orderhub.inventory.application.service.RecordInventoryMovementService;
import io.github.piresrenan.orderhub.inventory.domain.model.*;

/** PostgreSQL, not mocks, proves retry arbitration, stock arithmetic and atomic rollback. */
@Testcontainers
class PostgreSqlInventoryMovementAcceptanceTest {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(DockerImageName.parse(
            "postgres:18.6-trixie@sha256:4ef4dbc939d61acea57712655ddb4b4ab27419c913f94cca0cd57cb3ea3c2280")
            .asCompatibleSubstituteFor("postgres"));
    static JdbcTemplate jdbc;
    static DriverManagerDataSource source;
    static TransactionTemplate template;
    UUID tenant;
    UUID actor;
    UUID variant;
    InventoryMovementRepository repository;
    RecordInventoryMovementService service;

    @BeforeAll static void migrate() {
        source = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(source).load().migrate();
        jdbc = new JdbcTemplate(source);
        template = new TransactionTemplate(new DataSourceTransactionManager(source));
        template.setTimeout(3);
    }
    @BeforeEach void setup() {
        tenant = UUID.randomUUID(); actor = UUID.randomUUID(); variant = UUID.randomUUID();
        repository = new PostgreSqlInventoryMovementRepository(jdbc);
        service = service(repository, (t, v) -> { });
    }
    private RecordInventoryMovementService service(InventoryMovementRepository repo, InventoryVariantIdentityValidator identities) {
        return new RecordInventoryMovementService((a, t, action) -> { },
                new SpringInventoryAdministrationTransactionExecutor(template), identities, repo, Instant::now);
    }
    private InventoryMovementCommand command(UUID operation, InventoryMovementType type, long delta) {
        return new InventoryMovementCommand(actor, tenant, operation, variant, type, delta, "STOCK_COUNT", UUID.randomUUID());
    }
    private InventoryMovementCommand receipt(long delta) {
        return command(UUID.randomUUID(), InventoryMovementType.RECEIPT, delta);
    }
    private long count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM inventory." + table + " WHERE tenant_id = ?", Long.class, tenant);
    }
    private long onHand() {
        return jdbc.queryForObject("SELECT on_hand FROM inventory.inventory_positions WHERE tenant_id=? AND variant_id=?",
                Long.class, tenant, variant);
    }
    private void position(long onHand, long committed, long backordered, long safety) {
        jdbc.update("INSERT INTO inventory.inventory_positions VALUES (?,?,?,?,?,?)", tenant, variant,
                onHand, committed, backordered, safety);
    }

    @Test void initialReceiptCreatesPositionAndOneStableMovementWithoutInventingPolicy() {
        var result = service.record(receipt(10));
        assertThat(result.delta()).isEqualTo(10);
        assertThat(onHand()).isEqualTo(10);
        assertThat(count("movements")).isEqualTo(1);
        assertThat(count("tenant_policies")).isZero();
    }
    @Test void lostResponseReplayDoesNotRepeatReceiptOrChangeOriginalEvidence() {
        var request = receipt(10);
        var first = service.record(request);
        var replay = service.record(command(request.operationId(), request.type(), request.delta()));
        assertThat(replay).isEqualTo(first);
        assertThat(onHand()).isEqualTo(10);
        assertThat(count("movements")).isEqualTo(1);
    }
    @Test void correctionReplayDoesNotRepeatNegativeDeltaOrAlterCommitments() {
        position(10, 5, 7, 9);
        var request = command(UUID.randomUUID(), InventoryMovementType.ADJUSTMENT, -4);
        assertThat(service.record(request)).isEqualTo(service.record(request));
        assertThat(onHand()).isEqualTo(6);
        assertThat(jdbc.queryForMap("SELECT committed,backordered,safety_stock FROM inventory.inventory_positions WHERE tenant_id=?", tenant))
                .containsEntry("committed", 5L).containsEntry("backordered", 7L).containsEntry("safety_stock", 9L);
    }
    @Test void changedIntentOrActorCannotReuseSuccessfulOperation() {
        var request = receipt(10);
        service.record(request);
        var changes = java.util.List.of(
                command(request.operationId(), InventoryMovementType.RECEIPT, 11),
                command(request.operationId(), InventoryMovementType.ADJUSTMENT, 10),
                new InventoryMovementCommand(UUID.randomUUID(), tenant, request.operationId(), variant,
                        request.type(), 10, request.reason(), UUID.randomUUID()),
                new InventoryMovementCommand(actor, tenant, request.operationId(), UUID.randomUUID(),
                        request.type(), 10, request.reason(), UUID.randomUUID()),
                new InventoryMovementCommand(actor, tenant, request.operationId(), variant,
                        request.type(), 10, "DELIVERY", UUID.randomUUID()));
        for (var changed : changes) {
            assertThatThrownBy(() -> service.record(changed)).isInstanceOfSatisfying(InventoryAdministrationException.class,
                    ex -> assertThat(ex.reason()).isEqualTo(InventoryAdministrationException.Reason.CONFLICT));
        }
        assertThat(onHand()).isEqualTo(10);
        assertThat(count("movements")).isEqualTo(1);
    }
    @Test void invalidVariantRollsBackMovementAndNeverInitializesPosition() {
        var refusing = service(repository, (t, v) -> { throw new InventoryAdministrationException(
                InventoryAdministrationException.Reason.TARGET_UNAVAILABLE); });
        assertThatThrownBy(() -> refusing.record(receipt(10))).isInstanceOf(InventoryAdministrationException.class);
        assertThat(count("movements")).isZero();
        assertThat(count("inventory_positions")).isZero();
    }
    @Test void adjustmentRequiresPositionAndCannotEraseCommittedPhysicalStock() {
        assertThatThrownBy(() -> service.record(command(UUID.randomUUID(), InventoryMovementType.ADJUSTMENT, 2)))
                .isInstanceOf(InventoryAdministrationException.class);
        position(10, 9, 3, 0);
        assertThatThrownBy(() -> service.record(command(UUID.randomUUID(), InventoryMovementType.ADJUSTMENT, -2)))
                .isInstanceOfSatisfying(InventoryAdministrationException.class,
                        ex -> assertThat(ex.reason()).isEqualTo(InventoryAdministrationException.Reason.QUANTITY_CONFLICT));
        assertThat(onHand()).isEqualTo(10);
        assertThat(count("movements")).isZero();
    }
    @Test void receiptOverflowFailsClosedAndRollsBackMovement() {
        position(Long.MAX_VALUE, 0, 0, 0);
        assertThatThrownBy(() -> service.record(receipt(1))).isInstanceOfSatisfying(InventoryAdministrationException.class,
                ex -> assertThat(ex.reason()).isEqualTo(InventoryAdministrationException.Reason.QUANTITY_CONFLICT));
        assertThat(onHand()).isEqualTo(Long.MAX_VALUE);
        assertThat(count("movements")).isZero();
    }
    @Test void failureAfterPositionMutationRollsBackMovementAndNewPosition() {
        var failing = new InventoryMovementRepository() {
            public Optional<InventoryMovement> acquire(InventoryMovement m, String f) { return repository.acquire(m, f); }
            public void applyStockDelta(InventoryMovement m) {
                repository.applyStockDelta(m);
                throw new InventoryAdministrationException(InventoryAdministrationException.Reason.TECHNICAL);
            }
        };
        assertThatThrownBy(() -> service(failing, (t, v) -> { }).record(receipt(10)))
                .isInstanceOf(InventoryAdministrationException.class);
        assertThat(count("movements")).isZero();
        assertThat(count("inventory_positions")).isZero();
    }
    @Test void duplicateAcquisitionWaitsForOwnerAndReplaysAcrossIndependentConnections() throws Exception {
        proveDuplicateOwnerOutcome(false);
    }
    @Test void rolledBackOwnerAllowsWaitingDuplicateToExecuteOnce() throws Exception {
        proveDuplicateOwnerOutcome(true);
    }
    private void proveDuplicateOwnerOutcome(boolean rollback) throws Exception {
        var acquired = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var holding = new InventoryMovementRepository() {
            public Optional<InventoryMovement> acquire(InventoryMovement m, String f) {
                var result = repository.acquire(m, f);
                acquired.countDown(); awaitLatch(release);
                if (rollback) { throw new InventoryAdministrationException(InventoryAdministrationException.Reason.TECHNICAL); }
                return result;
            }
            public void applyStockDelta(InventoryMovement m) { repository.applyStockDelta(m); }
        };
        var request = receipt(10);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var owner = executor.submit(() -> service(holding, (t, v) -> { }).record(request));
            try {
                assertThat(acquired.await(5, TimeUnit.SECONDS)).isTrue();
                var contender = executor.submit(() -> service.record(request));
                await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock'",
                        Integer.class)).isPositive());
                release.countDown();
                if (rollback) { assertThatThrownBy(() -> owner.get(5, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class); }
                else { assertThat(contender.get(5, TimeUnit.SECONDS)).isEqualTo(owner.get(5, TimeUnit.SECONDS)); }
                assertThat(contender.get(5, TimeUnit.SECONDS).delta()).isEqualTo(10);
            } finally { release.countDown(); }
        }
        assertThat(onHand()).isEqualTo(10);
        assertThat(count("movements")).isEqualTo(1);
    }
    @Test void concurrentFirstReceiptsConvergeOnOnePositionWithExactArithmetic() throws Exception {
        var ready = new CountDownLatch(2);
        var concurrentService = service(repository, (t, v) -> { ready.countDown(); awaitLatch(ready); });
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> concurrentService.record(receipt(7)));
            var second = executor.submit(() -> concurrentService.record(receipt(11)));
            first.get(5, TimeUnit.SECONDS); second.get(5, TimeUnit.SECONDS);
        }
        assertThat(onHand()).isEqualTo(18);
        assertThat(count("inventory_positions")).isEqualTo(1);
        assertThat(count("movements")).isEqualTo(2);
    }
    @Test void sameOperationIdentityInAnotherTenantHasIndependentAuthority() {
        var request = receipt(10);
        service.record(request);
        var foreign = UUID.randomUUID();
        service.record(new InventoryMovementCommand(actor, foreign, request.operationId(), variant,
                request.type(), 3, request.reason(), UUID.randomUUID()));
        assertThat(onHand()).isEqualTo(10);
        assertThat(jdbc.queryForObject("SELECT on_hand FROM inventory.inventory_positions WHERE tenant_id=?", Long.class, foreign))
                .isEqualTo(3);
    }
    @Test void movementEvidenceCannotBeUpdatedDeletedOrTruncated() {
        service.record(receipt(10));
        for (String mutation : java.util.List.of("UPDATE inventory.movements SET delta=99 WHERE tenant_id='" + tenant + "'",
                "DELETE FROM inventory.movements WHERE tenant_id='" + tenant + "'", "TRUNCATE inventory.movements")) {
            assertThatThrownBy(() -> jdbc.execute(mutation)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        }
        assertThat(count("movements")).isEqualTo(1);
    }
    @Test void repositoryRefusesAutocommitThatCouldSeparateMovementFromStock() {
        var movement = new InventoryMovement(tenant, UUID.randomUUID(), actor, variant, InventoryMovementType.RECEIPT,
                1, "DELIVERY", UUID.randomUUID(), Instant.now());
        assertThatThrownBy(() -> repository.acquire(movement, "a".repeat(64)))
                .isInstanceOf(InventoryAdministrationException.class);
        assertThat(count("movements")).isZero();
    }
    /** Why: policy changes must be desired-state and auditable. Covers: initial, replay and stale policy.
     * Prevents: silently overwriting newer policy or duplicating successful evidence on retry. */
    @Test void policyDesiredStateIsAuditedAndStaleChangesConflict() {
        var policies=policyService();
        policies.policy(actor,tenant,null,InventoryPolicy.DENY,"POLICY_CHANGE",UUID.randomUUID());
        policies.policy(actor,tenant,null,InventoryPolicy.DENY,"POLICY_CHANGE",UUID.randomUUID());
        assertThat(count("policy_changes")).isEqualTo(1);
        policies.policy(actor,tenant,InventoryPolicy.DENY,InventoryPolicy.ALLOW_BACKORDER,"POLICY_CHANGE",UUID.randomUUID());
        assertThatThrownBy(()->policies.policy(actor,tenant,InventoryPolicy.DENY,InventoryPolicy.DENY,"POLICY_CHANGE",UUID.randomUUID()))
                .isInstanceOf(InventoryAdministrationException.class);
        assertThat(count("policy_changes")).isEqualTo(2);
    }
    /** Why: safety is commercial availability rather than allocation. Covers: above-stock, replay and stale CAS.
     * Prevents: negative safety, erased commitments and non-atomic evidence. */
    @Test void safetyDesiredStatePreservesAllPhysicalCounters() {
        position(10,8,3,0);
        var policies=policyService();
        policies.safetyStock(actor,tenant,variant,0,20,"SAFETY_CHANGE",UUID.randomUUID());
        policies.safetyStock(actor,tenant,variant,0,20,"SAFETY_CHANGE",UUID.randomUUID());
        assertThat(count("policy_changes")).isEqualTo(1);
        assertThatThrownBy(()->policies.safetyStock(actor,tenant,variant,0,1,"SAFETY_CHANGE",UUID.randomUUID()))
                .isInstanceOf(InventoryAdministrationException.class);
        assertThat(onHand()).isEqualTo(10);
        assertThat(jdbc.queryForObject("SELECT committed FROM inventory.inventory_positions WHERE tenant_id=?",Long.class,tenant)).isEqualTo(8);
        assertThat(jdbc.queryForObject("SELECT safety_stock FROM inventory.inventory_positions WHERE tenant_id=?",Long.class,tenant)).isEqualTo(20);
    }
    private io.github.piresrenan.orderhub.inventory.application.service.InventoryPolicyAdministrationService policyService() {
        return new io.github.piresrenan.orderhub.inventory.application.service.InventoryPolicyAdministrationService(
                (u,t,a)->{},new SpringInventoryAdministrationTransactionExecutor(template),
                new PostgreSqlInventoryPolicyAdministrationRepository(jdbc),Instant::now);
    }
    /** Why: an Order's accepted policy must remain authoritative until its commitment finishes.
     * Covers: the production policy reader and a concurrent updater on independent connections.
     * Prevents: accepting an obsolete ALLOW_BACKORDER after a DENY change has committed. */
    @Test void orderPolicyObservationBlocksConcurrentAdministrativeChange() throws Exception {
        policyService().policy(actor,tenant,null,InventoryPolicy.ALLOW_BACKORDER,"INITIAL",UUID.randomUUID());
        var read=new CountDownLatch(1); var release=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var order=pool.submit(()->template.execute(status->{
                var policy=new PostgreSqlInventoryPolicyRepository(jdbc).findByTenantId(tenant);
                read.countDown(); awaitLatch(release); return policy;
            }));
            try {
                assertThat(read.await(5,TimeUnit.SECONDS)).isTrue();
                var change=pool.submit(()->policyService().policy(actor,tenant,InventoryPolicy.ALLOW_BACKORDER,
                        InventoryPolicy.DENY,"CHANGE",UUID.randomUUID()));
                await().atMost(Duration.ofSeconds(2)).until(()->change.isDone() || jdbc.queryForObject(
                        "SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock'",Integer.class)>0);
                assertThat(change.isDone()).as("administration must wait for the Order policy observation").isFalse();
                release.countDown();
                assertThat(order.get(5,TimeUnit.SECONDS)).contains(InventoryPolicy.ALLOW_BACKORDER);
                assertThat(change.get(5,TimeUnit.SECONDS)).isEqualTo(InventoryPolicy.DENY);
            } finally { release.countDown(); }
        }
    }
    /** Why: evidence is part of the authoritative transaction. Covers: failed policy and safety audit INSERT.
     * Prevents: policy/availability changes succeeding without the required forensic evidence. */
    @Test void policyEvidenceFailureRollsBackBothKindsOfDesiredState() {
        position(10,2,1,0);
        jdbc.execute("""
                CREATE FUNCTION inventory.test_reject_policy_evidence() RETURNS TRIGGER LANGUAGE plpgsql AS $$
                BEGIN IF NEW.reason='FAIL_AUDIT' THEN RAISE EXCEPTION 'synthetic policy evidence failure'; END IF; RETURN NEW; END; $$
                """);
        jdbc.execute("CREATE TRIGGER test_policy_evidence BEFORE INSERT ON inventory.policy_changes FOR EACH ROW EXECUTE FUNCTION inventory.test_reject_policy_evidence()");
        try {
            assertThatThrownBy(()->policyService().policy(actor,tenant,null,InventoryPolicy.DENY,"FAIL_AUDIT",UUID.randomUUID()))
                    .isInstanceOf(InventoryAdministrationException.class);
            assertThat(count("tenant_policies")).isZero();
            assertThatThrownBy(()->policyService().safetyStock(actor,tenant,variant,0,5,"FAIL_AUDIT",UUID.randomUUID()))
                    .isInstanceOf(InventoryAdministrationException.class);
            assertThat(jdbc.queryForObject("SELECT safety_stock FROM inventory.inventory_positions WHERE tenant_id=?",Long.class,tenant)).isZero();
            assertThat(count("policy_changes")).isZero();
        } finally {
            jdbc.execute("DROP TRIGGER test_policy_evidence ON inventory.policy_changes");
            jdbc.execute("DROP FUNCTION inventory.test_reject_policy_evidence()");
        }
    }
    private static void awaitLatch(CountDownLatch latch) {
        try { if (!latch.await(5, TimeUnit.SECONDS)) { throw new AssertionError("Deterministic test barrier was not reached"); } }
        catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw new AssertionError(exception); }
    }
}
