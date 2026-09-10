package io.github.piresrenan.orderhub.workforce.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.util.UUID;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import io.github.piresrenan.orderhub.support.PostgreSqlTestConfiguration;
import io.github.piresrenan.orderhub.users.application.port.in.*;
import io.github.piresrenan.orderhub.workforce.application.port.in.*;
import io.github.piresrenan.orderhub.workforce.application.port.out.StaffMaterializationRepository;
import io.github.piresrenan.orderhub.workforce.application.port.out.StaffProvisioningIntentPersistenceException;
import io.github.piresrenan.orderhub.workforce.application.model.*;
import io.github.piresrenan.orderhub.workforce.application.service.StaffProvisioningUnavailableException;
import io.github.piresrenan.orderhub.authorization.application.port.in.provisioning.StaffProvisioningAuthorizationDeniedException;

/**
 * Why: separate module tests cannot certify a fully composed provisioning transaction.
 * Covers: real Users, Workforce, Authorization, Tenant eligibility and both audit writers.
 * Prevents: production-only partial commits, stale-actor grants and false replay success.
 * Identity inputs are trusted synthetic facts here; signed-JWT acceptance is a separate gate.
 */
@SpringBootTest
@Import(PostgreSqlTestConfiguration.class)
class StaffProvisioningProductionIntegrationTest {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager manager;
    @Autowired private ResolveOrCreateExternalUserUseCase users;
    @Autowired private ResolveExternalIdentityUseCase resolver;
    @Autowired private EnsureActiveTenantMembershipUseCase memberships;
    @Autowired private StaffMaterializationRepository staff;
    @Autowired private IssueStaffProvisioningIntentUseCase issuance;
    @Autowired private ConsumeStaffProvisioningUseCase consumption;
    @Autowired private ManageStaffProvisioningUseCase administration;
    @Autowired private io.github.piresrenan.orderhub.workforce.application.port.out.StaffProvisioningIntentRepository intentRepository;
    @Autowired private io.github.piresrenan.orderhub.workforce.application.port.out.StaffProvisioningCompletion completion;

    @Test
    void completeRuntimeCreatesExactlyOneAuthorizedAuditedStaff() {
        var fixture = fixture();
        var staffId = consume(fixture);
        var userId = resolver.resolve(identity(fixture)).orElseThrow().userId();
        assertThat(jdbc.queryForObject("SELECT staff_id FROM workforce.staff_profiles WHERE user_id = ? AND tenant_id = ?",
                UUID.class, userId, fixture.tenant())).isEqualTo(staffId);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM access_control.role_assignments WHERE user_id = ? AND tenant_id = ?",
                Integer.class, userId, fixture.tenant())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT subject_user_id FROM workforce.provisioning_events WHERE intent_id = ? AND action = 'CONSUMED'",
                UUID.class, fixture.issued().intentId())).isEqualTo(userId);
        assertThat(jdbc.queryForObject("SELECT target_user_id FROM access_control.staff_provisioning_role_events WHERE intent_id = ?",
                UUID.class, fixture.issued().intentId())).isEqualTo(userId);
        assertThatThrownBy(() -> consume(fixture)).isInstanceOf(StaffProvisioningUnavailableException.class);
    }

    @Test
    void finalWorkforceEvidenceFailureRollsBackRoleEvidenceAndEveryNewRelationship() {
        var fixture = fixture();
        var beforeUsers = jdbc.queryForObject("SELECT count(*) FROM users.users", Integer.class);
        jdbc.execute("ALTER TABLE workforce.provisioning_events ADD CONSTRAINT ck_synthetic_provisioning_failure CHECK (correlation_id <> '"
                + fixture.correlation() + "'::uuid)");
        try {
            assertThatThrownBy(() -> consume(fixture)).isInstanceOf(StaffProvisioningIntentPersistenceException.class);
            assertPendingWithoutTarget(fixture);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM users.users", Integer.class)).isEqualTo(beforeUsers);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM access_control.staff_provisioning_role_events WHERE intent_id = ?",
                    Integer.class, fixture.issued().intentId())).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM users.tenant_memberships WHERE tenant_id = ?",
                    Integer.class, fixture.tenant())).isEqualTo(1);
        } finally {
            jdbc.execute("ALTER TABLE workforce.provisioning_events DROP CONSTRAINT ck_synthetic_provisioning_failure");
        }
        assertThat(consume(fixture)).isNotNull();
    }

    @Test
    void authorizationEvidenceFailureCannotSpendTheProofOrPartiallyAssignARole() {
        var fixture = fixture();
        jdbc.execute("ALTER TABLE access_control.staff_provisioning_role_events ADD CONSTRAINT ck_synthetic_role_failure CHECK (correlation_id <> '"
                + fixture.correlation() + "'::uuid)");
        try {
            assertThatThrownBy(() -> consume(fixture))
                    .isInstanceOf(io.github.piresrenan.orderhub.authorization.application.port.out.AuthorizationPersistenceException.class);
            assertPendingWithoutTarget(fixture);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM access_control.role_assignments WHERE tenant_id = ?",
                    Integer.class, fixture.tenant())).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM workforce.provisioning_events WHERE intent_id = ?",
                    Integer.class, fixture.issued().intentId())).isZero();
        } finally {
            jdbc.execute("ALTER TABLE access_control.staff_provisioning_role_events DROP CONSTRAINT ck_synthetic_role_failure");
        }
    }

    @Test
    void revokedIssuingAuthorityPreventsAnyNewIdentityEffect() {
        var fixture = fixture();
        jdbc.update("DELETE FROM access_control.role_assignments WHERE user_id = ? AND tenant_id = ?",
                fixture.actor(), fixture.tenant());
        assertThatThrownBy(() -> consume(fixture)).isInstanceOf(StaffProvisioningAuthorizationDeniedException.class);
        assertPendingWithoutTarget(fixture);
    }

    @Test
    void overbroadInitialRoleIsRejectedWithoutClipping() {
        var fixture = fixture();
        jdbc.update("INSERT INTO access_control.role_permissions (role_id, permission_code) VALUES (?, 'INVENTORY_ADJUST')", fixture.targetRole());
        assertThatThrownBy(() -> consume(fixture)).isInstanceOf(StaffProvisioningAuthorizationDeniedException.class);
        assertPendingWithoutTarget(fixture);
    }

    @Test
    void concurrentProductionConsumersHaveOneCompleteEffectForOneProof() throws Exception {
        var executor = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 32; round++) {
                var fixture = fixture();
                var ready = new CountDownLatch(2);
                var start = new CountDownLatch(1);
                var rejections = new java.util.concurrent.ConcurrentLinkedQueue<StaffProvisioningUnavailableException>();
                java.util.concurrent.Callable<Boolean> work = () -> {
                    ready.countDown();
                    assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                    try { consume(fixture); return true; }
                    catch (StaffProvisioningUnavailableException exception) { rejections.add(exception); return false; }
                };
                var first = executor.submit(work);
                var second = executor.submit(work);
                assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
                start.countDown();
                var successes = (first.get(20, TimeUnit.SECONDS) ? 1 : 0) + (second.get(20, TimeUnit.SECONDS) ? 1 : 0);
                if (successes != 1) {
                    var failure = new AssertionError("Expected one consumer; actual=" + successes + "; times="
                            + jdbc.queryForMap("SELECT created_at, expires_at, consumed_at, clock_timestamp() AS database_now FROM workforce.staff_provisioning_intents WHERE intent_id = ?",
                                    fixture.issued().intentId()) + "; application_now=" + java.time.OffsetDateTime.now());
                    rejections.forEach(failure::addSuppressed);
                    throw failure;
                }
                assertThat(jdbc.queryForObject("SELECT count(*) FROM workforce.provisioning_events WHERE intent_id = ? AND action = 'CONSUMED'",
                        Integer.class, fixture.issued().intentId())).isEqualTo(1);
                assertThat(jdbc.queryForObject("SELECT count(*) FROM access_control.staff_provisioning_role_events WHERE intent_id = ?",
                        Integer.class, fixture.issued().intentId())).isEqualTo(1);
            }
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(20, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void differentTenantsContendingForOneExternalIdentityCreateNoOrphanUser() throws Exception {
        var executor = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 32; round++) {
                var firstFixture = fixture();
                var secondOriginal = fixture();
                var secondFixture = new Fixture(secondOriginal.tenant(), secondOriginal.actor(), secondOriginal.targetRole(),
                        secondOriginal.correlation(), firstFixture.subject(), secondOriginal.issued());
                var beforeUsers = jdbc.queryForObject("SELECT count(*) FROM users.users", Integer.class);
                var ready = new CountDownLatch(2);
                var start = new CountDownLatch(1);
                java.util.concurrent.Callable<UUID> firstWork = () -> {
                    ready.countDown();
                    assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                    return consume(firstFixture);
                };
                java.util.concurrent.Callable<UUID> secondWork = () -> {
                    ready.countDown();
                    assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                    return consume(secondFixture);
                };
                var first = executor.submit(firstWork);
                var second = executor.submit(secondWork);
                assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
                start.countDown();
                var firstStaff = first.get(20, TimeUnit.SECONDS);
                var secondStaff = second.get(20, TimeUnit.SECONDS);
                var firstUser = jdbc.queryForObject("SELECT user_id FROM workforce.staff_profiles WHERE staff_id = ?", UUID.class, firstStaff);
                var secondUser = jdbc.queryForObject("SELECT user_id FROM workforce.staff_profiles WHERE staff_id = ?", UUID.class, secondStaff);
                assertThat(firstUser).isEqualTo(secondUser);
                assertThat(jdbc.queryForObject("SELECT count(*) FROM users.users", Integer.class)).isEqualTo(beforeUsers + 1);
                assertThat(jdbc.queryForObject("SELECT count(*) FROM users.external_identity_bindings WHERE issuer = ? AND subject = ?",
                        Integer.class, identity(firstFixture).issuer(), firstFixture.subject())).isEqualTo(1);
            }
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(20, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void proofExpiringWhileWaitingForItsRowCannotCreateAnyRelationship() throws Exception {
        var fixture = fixture();
        jdbc.update("UPDATE workforce.staff_provisioning_intents SET expires_at = clock_timestamp() + interval '3 seconds' WHERE intent_id = ?",
                fixture.issued().intentId());
        var executor = Executors.newFixedThreadPool(2);
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var waiterPid = new java.util.concurrent.ArrayBlockingQueue<Integer>(1);
        try {
            var owner = executor.submit(() -> new TransactionTemplate(manager).execute(status -> {
                jdbc.queryForObject("SELECT intent_id FROM workforce.staff_provisioning_intents WHERE intent_id = ? FOR UPDATE",
                        UUID.class, fixture.issued().intentId());
                locked.countDown();
                try { assertThat(release.await(12, TimeUnit.SECONDS)).isTrue(); }
                catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw new AssertionError(exception); }
                return true;
            }));
            assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
            var consumer = executor.submit(() -> new TransactionTemplate(manager).execute(status -> {
                waiterPid.add(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                return consume(fixture);
            }));
            var pid = waiterPid.poll(5, TimeUnit.SECONDS);
            assertThat(pid).isNotNull();
            org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(2)).untilAsserted(() ->
                    assertThat(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM pg_locks WHERE pid = ? AND NOT granted)",
                            Boolean.class, pid)).isTrue());
            assertThat(jdbc.queryForObject("SELECT clock_timestamp() < expires_at FROM workforce.staff_provisioning_intents WHERE intent_id = ?",
                    Boolean.class, fixture.issued().intentId())).isTrue();
            org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(5)).until(() ->
                    jdbc.queryForObject("SELECT clock_timestamp() >= expires_at FROM workforce.staff_provisioning_intents WHERE intent_id = ?",
                            Boolean.class, fixture.issued().intentId()));
            release.countDown();
            assertThat(owner.get(5, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> consumer.get(5, TimeUnit.SECONDS))
                    .hasRootCauseInstanceOf(StaffProvisioningUnavailableException.class);
            assertPendingWithoutTarget(fixture);
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(15, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void cancellationCannotDeadlockAConsumerAlreadyHoldingItsIntent() throws Exception {
        var fixture = fixture();
        var executor = Executors.newFixedThreadPool(2);
        var ownsIntent = new CountDownLatch(1);
        var proceed = new CountDownLatch(1);
        var cancelPid = new java.util.concurrent.ArrayBlockingQueue<Integer>(1);
        try {
            var consumer = executor.submit(() -> new TransactionTemplate(manager).execute(status -> {
                jdbc.queryForObject("SELECT intent_id FROM workforce.staff_provisioning_intents WHERE intent_id = ? FOR UPDATE",
                        UUID.class, fixture.issued().intentId());
                ownsIntent.countDown();
                try { assertThat(proceed.await(10, TimeUnit.SECONDS)).isTrue(); }
                catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw new AssertionError(exception); }
                consume(fixture);
                return true;
            }));
            assertThat(ownsIntent.await(5, TimeUnit.SECONDS)).isTrue();
            var cancellation = executor.submit(() -> new TransactionTemplate(manager).execute(status -> {
                cancelPid.add(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                return administration.cancel(fixture.actor(), fixture.tenant(), fixture.issued().intentId(), UUID.randomUUID());
            }));
            var pid = cancelPid.poll(5, TimeUnit.SECONDS);
            assertThat(pid).isNotNull();
            org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(5)).untilAsserted(() ->
                    assertThat(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM pg_locks WHERE pid = ? AND NOT granted)",
                            Boolean.class, pid)).isTrue());
            proceed.countDown();
            assertThat(consumer.get(10, TimeUnit.SECONDS)).isTrue();
            assertThat(cancellation.get(10, TimeUnit.SECONDS)).isFalse();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM workforce.provisioning_events WHERE intent_id = ? AND action = 'CONSUMED'",
                    Integer.class, fixture.issued().intentId())).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM workforce.provisioning_events WHERE intent_id = ? AND action = 'CANCELLED'",
                    Integer.class, fixture.issued().intentId())).isZero();
        } finally {
            proceed.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(15, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void cancellationAuthorityRevokedDuringIntentWaitRollsBackItsTerminalChange() throws Exception {
        var fixture = fixture();
        var executor = Executors.newFixedThreadPool(2);
        var ownsIntent = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var cancelPid = new java.util.concurrent.ArrayBlockingQueue<Integer>(1);
        try {
            var owner = executor.submit(() -> new TransactionTemplate(manager).execute(status -> {
                jdbc.queryForObject("SELECT intent_id FROM workforce.staff_provisioning_intents WHERE intent_id = ? FOR UPDATE",
                        UUID.class, fixture.issued().intentId());
                ownsIntent.countDown();
                try { assertThat(release.await(10, TimeUnit.SECONDS)).isTrue(); }
                catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw new AssertionError(exception); }
                return true;
            }));
            assertThat(ownsIntent.await(5, TimeUnit.SECONDS)).isTrue();
            var cancellation = executor.submit(() -> new TransactionTemplate(manager).execute(status -> {
                cancelPid.add(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                return administration.cancel(fixture.actor(), fixture.tenant(), fixture.issued().intentId(), UUID.randomUUID());
            }));
            var pid = cancelPid.poll(5, TimeUnit.SECONDS);
            assertThat(pid).isNotNull();
            org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(5)).untilAsserted(() ->
                    assertThat(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM pg_locks WHERE pid = ? AND NOT granted)",
                            Boolean.class, pid)).isTrue());
            jdbc.update("DELETE FROM access_control.role_assignments WHERE user_id = ? AND tenant_id = ?", fixture.actor(), fixture.tenant());
            release.countDown();
            assertThat(owner.get(5, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> cancellation.get(5, TimeUnit.SECONDS))
                    .hasRootCauseInstanceOf(StaffProvisioningAuthorizationDeniedException.class);
            assertThat(jdbc.queryForObject("SELECT cancelled_at IS NULL FROM workforce.staff_provisioning_intents WHERE intent_id = ?",
                    Boolean.class, fixture.issued().intentId())).isTrue();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM workforce.provisioning_events WHERE intent_id = ? AND action = 'CANCELLED'",
                    Integer.class, fixture.issued().intentId())).isZero();
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(15, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void authorizedIssuanceWritesOneEventAndReplayCannotRecoverTheCredential() {
        var fixture = fixture();
        var command = issuanceCommand(fixture, UUID.randomUUID());
        var issued = (StaffProvisioningIssuance.Issued) administration.issue(command);
        var replay = administration.issue(command);
        assertThat(replay).isEqualTo(new StaffProvisioningIssuance.Replay(issued.intentId()));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM workforce.provisioning_events WHERE intent_id = ? AND action = 'ISSUED'",
                Integer.class, issued.intentId())).isEqualTo(1);
    }

    @Test
    void issuanceEvidenceFailureRollsBackTheNewProofAndPermitsRetry() {
        var fixture = fixture();
        var command = issuanceCommand(fixture, UUID.randomUUID());
        jdbc.execute("ALTER TABLE workforce.provisioning_events ADD CONSTRAINT ck_synthetic_issue_failure CHECK (correlation_id <> '"
                + command.correlationId() + "'::uuid)");
        try {
            assertThatThrownBy(() -> administration.issue(command)).isInstanceOf(StaffProvisioningIntentPersistenceException.class);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM workforce.staff_provisioning_intents WHERE tenant_id = ? AND operation_id = ?",
                    Integer.class, fixture.tenant(), command.operationId())).isZero();
        } finally {
            jdbc.execute("ALTER TABLE workforce.provisioning_events DROP CONSTRAINT ck_synthetic_issue_failure");
        }
        assertThat(administration.issue(command)).isInstanceOf(StaffProvisioningIssuance.Issued.class);
    }

    @Test
    void cancellationEvidenceFailureRollsBackTheTerminalChangeAndPermitsRetry() {
        var fixture = fixture();
        jdbc.execute("ALTER TABLE workforce.provisioning_events ADD CONSTRAINT ck_synthetic_cancel_failure CHECK (correlation_id <> '"
                + fixture.correlation() + "'::uuid)");
        try {
            assertThatThrownBy(() -> administration.cancel(fixture.actor(), fixture.tenant(), fixture.issued().intentId(), fixture.correlation()))
                    .isInstanceOf(StaffProvisioningIntentPersistenceException.class);
            assertThat(jdbc.queryForObject("SELECT cancelled_at IS NULL FROM workforce.staff_provisioning_intents WHERE intent_id = ?",
                    Boolean.class, fixture.issued().intentId())).isTrue();
        } finally {
            jdbc.execute("ALTER TABLE workforce.provisioning_events DROP CONSTRAINT ck_synthetic_cancel_failure");
        }
        assertThat(administration.cancel(fixture.actor(), fixture.tenant(), fixture.issued().intentId(), fixture.correlation())).isTrue();
        assertThat(administration.cancel(fixture.actor(), fixture.tenant(), fixture.issued().intentId(), fixture.correlation())).isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM workforce.provisioning_events WHERE intent_id = ? AND action = 'CANCELLED'",
                Integer.class, fixture.issued().intentId())).isEqualTo(1);
        assertThatThrownBy(() -> consume(fixture)).isInstanceOf(StaffProvisioningUnavailableException.class);
    }

    @Test
    void issuanceReplayCannotDeadlockConsumptionOfItsExistingProof() throws Exception {
        var fixture = fixture();
        var operation = jdbc.queryForObject("SELECT operation_id FROM workforce.staff_provisioning_intents WHERE intent_id = ?",
                UUID.class, fixture.issued().intentId());
        var command = issuanceCommand(fixture, operation);
        var digest = java.security.MessageDigest.getInstance("SHA-256").digest(java.util.Base64.getUrlDecoder().decode(fixture.issued().credential()));
        var executor = Executors.newFixedThreadPool(2);
        var ownsIntent = new CountDownLatch(1);
        var proceed = new CountDownLatch(1);
        var replayPid = new java.util.concurrent.ArrayBlockingQueue<Integer>(1);
        try {
            var consumer = executor.submit(() -> new TransactionTemplate(manager).execute(status -> {
                // Pause at the real consumption boundary between atomic acquisition
                // and current-authority checks; rollback keeps this lock probe isolated.
                var intent = intentRepository.consumePending(digest, jdbc.queryForObject("SELECT clock_timestamp()", java.time.OffsetDateTime.class)).orElseThrow();
                ownsIntent.countDown();
                try { assertThat(proceed.await(10, TimeUnit.SECONDS)).isTrue(); }
                catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw new AssertionError(exception); }
                completion.authorize(intent);
                status.setRollbackOnly();
                return true;
            }));
            assertThat(ownsIntent.await(5, TimeUnit.SECONDS)).isTrue();
            var replay = executor.submit(() -> new TransactionTemplate(manager).execute(status -> {
                replayPid.add(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                return administration.issue(command);
            }));
            var pid = replayPid.poll(5, TimeUnit.SECONDS);
            assertThat(pid).isNotNull();
            // The corrected replay may finish without waiting. The old path
            // instead reaches an observed database wait before we resume the
            // consumer, deterministically exposing its inverse lock order.
            org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(5)).until(() ->
                    replay.isDone() || Boolean.TRUE.equals(jdbc.queryForObject(
                            "SELECT EXISTS (SELECT 1 FROM pg_locks WHERE pid = ? AND NOT granted)", Boolean.class, pid)));
            proceed.countDown();
            assertThat(consumer.get(10, TimeUnit.SECONDS)).isTrue();
            assertThat(replay.get(10, TimeUnit.SECONDS)).isEqualTo(new StaffProvisioningIssuance.Replay(fixture.issued().intentId()));
        } finally {
            proceed.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(15, TimeUnit.SECONDS)).isTrue();
            java.util.Arrays.fill(digest, (byte) 0);
        }
    }

    private IssueStaffProvisioningIntentCommand issuanceCommand(Fixture fixture, UUID operationId) {
        return jdbc.queryForObject("SELECT department_id, position_id, initial_role_code FROM workforce.staff_provisioning_intents WHERE intent_id = ?",
                (row, index) -> new IssueStaffProvisioningIntentCommand(fixture.tenant(), fixture.actor(), row.getObject("department_id", UUID.class),
                        row.getObject("position_id", UUID.class), row.getString("initial_role_code"), operationId, fixture.correlation()), fixture.issued().intentId());
    }

    private void assertPendingWithoutTarget(Fixture fixture) {
        assertThat(resolver.resolve(identity(fixture))).isEmpty();
        assertThat(jdbc.queryForObject("SELECT consumed_at IS NULL FROM workforce.staff_provisioning_intents WHERE intent_id = ?",
                Boolean.class, fixture.issued().intentId())).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM workforce.staff_profiles WHERE tenant_id = ?",
                Integer.class, fixture.tenant())).isEqualTo(1);
    }

    private UUID consume(Fixture fixture) {
        var applicationStart = java.time.OffsetDateTime.now();
        try {
            return consumption.consume(fixture.issued().credential(), identity(fixture).issuer(), fixture.subject());
        } catch (StaffProvisioningUnavailableException exception) {
            exception.addSuppressed(new AssertionError("application_start=" + applicationStart + "; times="
                    + jdbc.queryForMap("SELECT created_at, expires_at, consumed_at, clock_timestamp() AS database_now FROM workforce.staff_provisioning_intents WHERE intent_id = ?",
                            fixture.issued().intentId())));
            throw exception;
        }
    }

    private ResolveExternalIdentityQuery identity(Fixture fixture) {
        return new ResolveExternalIdentityQuery("https://synthetic-provisioning.test", fixture.subject());
    }

    private Fixture fixture() {
        var tenant = UUID.randomUUID();
        var department = UUID.randomUUID();
        var actorPosition = UUID.randomUUID();
        var targetPosition = UUID.randomUUID();
        var correlation = UUID.randomUUID();
        var actorId = users.resolveOrCreate(new ResolveExternalIdentityQuery("https://synthetic-provisioning.test", UUID.randomUUID().toString())).userId();
        jdbc.update("INSERT INTO tenants.tenants (id, name, status) VALUES (?, 'Synthetic provisioning Tenant', 'ACTIVE')", tenant);
        memberships.ensureActive(new EstablishTenantMembershipCommand(actorId, tenant));
        jdbc.update("INSERT INTO workforce.departments VALUES (?, ?, 'STAFF', 'Synthetic department')", department, tenant);
        jdbc.update("INSERT INTO workforce.job_positions VALUES (?, ?, 'MANAGER', 'Synthetic manager', 'MANAGEMENT')", actorPosition, tenant);
        jdbc.update("INSERT INTO workforce.job_positions VALUES (?, ?, 'OPERATOR', 'Synthetic operator', 'OPERATIONAL')", targetPosition, tenant);
        var actorPermissions = Set.of("TENANT_MEMBERS_MANAGE", "TENANT_ROLES_ASSIGN", "INVENTORY_VIEW");
        for (var permission : actorPermissions) {
            jdbc.update("INSERT INTO workforce.job_position_permissions VALUES (?, ?, ?)", tenant, actorPosition, permission);
        }
        jdbc.update("INSERT INTO workforce.job_position_permissions VALUES (?, ?, 'INVENTORY_VIEW')", tenant, targetPosition);
        new TransactionTemplate(manager).execute(status -> staff.materialize(tenant, actorId, department, actorPosition));
        var actorRole = role(tenant, "PROVISIONING_MANAGER", "MANAGEMENT", actorPermissions);
        var targetRole = role(tenant, "PROVISIONING_OPERATOR", "OPERATIONAL", Set.of("INVENTORY_VIEW"));
        jdbc.update("INSERT INTO access_control.role_assignments (assignment_id, user_id, tenant_id, persona, role_id) VALUES (?, ?, ?, 'STAFF', ?)",
                UUID.randomUUID(), actorId, tenant, actorRole);
        var issued = (StaffProvisioningIssuance.Issued) issuance.issue(new IssueStaffProvisioningIntentCommand(
                tenant, actorId, department, targetPosition, "PROVISIONING_OPERATOR", UUID.randomUUID(), correlation));
        return new Fixture(tenant, actorId, targetRole, correlation, UUID.randomUUID().toString(), issued);
    }

    private UUID role(UUID tenant, String code, String band, Set<String> permissions) {
        var id = UUID.randomUUID();
        jdbc.update("INSERT INTO access_control.role_definitions (role_id, tenant_id, code, persona, authority_band, mutability) VALUES (?, ?, ?, 'STAFF', ?, 'TENANT_CUSTOM')",
                id, tenant, code, band);
        for (var permission : permissions) {
            jdbc.update("INSERT INTO access_control.role_permissions (role_id, permission_code) VALUES (?, ?)", id, permission);
        }
        return id;
    }

    private record Fixture(UUID tenant, UUID actor, UUID targetRole, UUID correlation, String subject, StaffProvisioningIssuance.Issued issued) {}
}
