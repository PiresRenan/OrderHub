package io.github.piresrenan.orderhub.workforce.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import io.github.piresrenan.orderhub.support.PostgreSqlTestConfiguration;
import io.github.piresrenan.orderhub.users.application.port.in.ResolveExternalIdentityQuery;
import io.github.piresrenan.orderhub.users.application.port.in.ResolveOrCreateExternalUserUseCase;
import io.github.piresrenan.orderhub.workforce.application.model.StaffProvisioningIssuance;
import io.github.piresrenan.orderhub.workforce.application.port.in.ConsumeStaffProvisioningUseCase;
import io.github.piresrenan.orderhub.workforce.application.port.in.ColdStartStaffProvisioningUseCase;
import io.github.piresrenan.orderhub.workforce.application.port.in.IssueStaffProvisioningIntentUseCase;
import io.github.piresrenan.orderhub.workforce.application.model.IssueStaffProvisioningIntentCommand;
import io.github.piresrenan.orderhub.workforce.application.service.StaffProvisioningUnavailableException;
import io.github.piresrenan.orderhub.authorization.application.port.in.provisioning.StaffProvisioningAuthorizationDeniedException;
import io.github.piresrenan.orderhub.authorization.application.port.out.AuthorizationPersistenceException;
import io.github.piresrenan.orderhub.workforce.application.port.in.authorization.AuthorizeStaffTenantActionUseCase;
import io.github.piresrenan.orderhub.authorization.domain.model.AuthorizationDecision;
import io.github.piresrenan.orderhub.authorization.domain.model.PermissionCode;

/** Proves first Staff setup starts from an actually empty Tenant and never becomes a recurring Platform bypass. */
@SpringBootTest
@Import(PostgreSqlTestConfiguration.class)
class StaffColdStartProductionIntegrationTest {
    @Autowired private ColdStartStaffProvisioningUseCase coldStart;
    @Autowired private IssueStaffProvisioningIntentUseCase primitive;
    @Autowired private io.github.piresrenan.orderhub.workforce.application.port.in.ManageStaffProvisioningUseCase management;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ResolveOrCreateExternalUserUseCase users;
    @Autowired private ConsumeStaffProvisioningUseCase consumption;
    @Autowired private AuthorizeStaffTenantActionUseCase staffAuthority;
    @Autowired private org.springframework.transaction.PlatformTransactionManager transactionManager;

    @Test
    void platformManagerBootstrapsOneViableStaffFromAnEmptyTenant() throws Exception {
        var fixture = fixture(true);
        var issued = issue(fixture);
        var staffId = consumption.consume(issued.credential(), "https://synthetic-cold-start.test", UUID.randomUUID().toString());
        var userId = jdbc.queryForObject("SELECT user_id FROM workforce.staff_profiles WHERE staff_id = ? AND tenant_id = ?",
                UUID.class, staffId, fixture.tenant());
        assertThat(userId).isNotEqualTo(fixture.actor());
        assertThat(staffAuthority.authorize(userId, fixture.tenant(), PermissionCode.TENANT_MEMBERS_MANAGE)).isEqualTo(AuthorizationDecision.ALLOW);
        assertThat(staffAuthority.authorize(userId, fixture.tenant(), PermissionCode.TENANT_ROLES_ASSIGN)).isEqualTo(AuthorizationDecision.ALLOW);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM workforce.staff_profiles WHERE tenant_id = ?", Integer.class, fixture.tenant())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM workforce.provisioning_events WHERE intent_id = ? AND action = 'COLD_START_ISSUED'",
                Integer.class, issued.intentId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM workforce.provisioning_events WHERE intent_id = ? AND action = 'CONSUMED'",
                Integer.class, issued.intentId())).isEqualTo(1);
        assertThatThrownBy(() -> issue(fixture)).isInstanceOf(RuntimeException.class)
                .hasMessage("Staff provisioning is unavailable");
    }

    @Test
    void internalUserWithoutPlatformGrantCannotPrepareTenantMetadata() {
        var fixture = fixture(false);
        assertThatThrownBy(() -> issue(fixture)).isInstanceOf(RuntimeException.class)
                .hasMessage("Staff provisioning authority is unavailable");
        assertNoPreparedMetadata(fixture);
    }

    @Test
    void evenInactiveHistoricalStaffPreventsColdStart() {
        var fixture = fixture(true);
        jdbc.update("INSERT INTO workforce.staff_profiles (staff_id, user_id, tenant_id, status) VALUES (?, ?, ?, 'INACTIVE')",
                UUID.randomUUID(), fixture.actor(), fixture.tenant());
        assertThatThrownBy(() -> issue(fixture)).isInstanceOf(RuntimeException.class)
                .hasMessage("Staff provisioning is unavailable");
        assertNoPreparedMetadata(fixture);
    }

    @Test
    void twoColdProofsCanProduceOnlyOneFirstStaff() throws Exception {
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 32; round++) {
                var fixture = fixture(true);
                var firstProof = issue(fixture);
                var secondProof = issue(fixture);
                var beforeUsers = jdbc.queryForObject("SELECT count(*) FROM users.users", Integer.class);
                var ready = new java.util.concurrent.CountDownLatch(2);
                var start = new java.util.concurrent.CountDownLatch(1);
                java.util.function.Function<StaffProvisioningIssuance.Issued, java.util.concurrent.Callable<Boolean>> work = proof -> () -> {
                    ready.countDown();
                    assertThat(start.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                    try {
                        consumption.consume(proof.credential(), "https://synthetic-cold-start.test", UUID.randomUUID().toString());
                        return true;
                    } catch (StaffProvisioningUnavailableException exception) { return false; }
                };
                var first = executor.submit(work.apply(firstProof));
                var second = executor.submit(work.apply(secondProof));
                assertThat(ready.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                start.countDown();
                assertThat((first.get(20, java.util.concurrent.TimeUnit.SECONDS) ? 1 : 0)
                        + (second.get(20, java.util.concurrent.TimeUnit.SECONDS) ? 1 : 0)).isEqualTo(1);
                assertThat(jdbc.queryForObject("SELECT count(*) FROM workforce.staff_profiles WHERE tenant_id = ?", Integer.class, fixture.tenant())).isEqualTo(1);
                assertThat(jdbc.queryForObject("SELECT count(*) FROM users.users", Integer.class)).isEqualTo(beforeUsers + 1);
                assertThat(jdbc.queryForObject("SELECT count(*) FROM workforce.provisioning_events WHERE tenant_id = ? AND action = 'CONSUMED'", Integer.class, fixture.tenant())).isEqualTo(1);
                assertThat(jdbc.queryForObject("SELECT count(*) FROM access_control.staff_provisioning_role_events WHERE tenant_id = ?", Integer.class, fixture.tenant())).isEqualTo(1);
            }
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(20, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void revokedPlatformGrantCannotConsumeItsIssuedColdProof() throws Exception {
        var fixture = fixture(true);
        var issued = issue(fixture);
        var beforeUsers = jdbc.queryForObject("SELECT count(*) FROM users.users", Integer.class);
        jdbc.update("DELETE FROM access_control.administrative_grants WHERE user_id = ?", fixture.actor());
        assertThatThrownBy(() -> consumption.consume(issued.credential(), "https://synthetic-cold-start.test", UUID.randomUUID().toString()))
                .isInstanceOf(StaffProvisioningAuthorizationDeniedException.class);
        assertThat(jdbc.queryForObject("SELECT consumed_at IS NULL FROM workforce.staff_provisioning_intents WHERE intent_id = ?", Boolean.class, issued.intentId())).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users.users", Integer.class)).isEqualTo(beforeUsers);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM workforce.staff_profiles WHERE tenant_id = ?", Integer.class, fixture.tenant())).isZero();
    }

    @Test
    void coldConsumptionAuditFailureRollsBackRoleCreationAndEveryRelationship() throws Exception {
        var fixture = fixture(true);
        var issued = issue(fixture);
        var correlation = jdbc.queryForObject("SELECT correlation_id FROM workforce.staff_provisioning_intents WHERE intent_id = ?", UUID.class, issued.intentId());
        var beforeUsers = jdbc.queryForObject("SELECT count(*) FROM users.users", Integer.class);
        jdbc.execute("ALTER TABLE access_control.staff_provisioning_role_events ADD CONSTRAINT ck_synthetic_cold_audit_failure CHECK (correlation_id <> '" + correlation + "'::uuid)");
        try {
            assertThatThrownBy(() -> consumption.consume(issued.credential(), "https://synthetic-cold-start.test", UUID.randomUUID().toString()))
                    .isInstanceOf(AuthorizationPersistenceException.class);
            assertThat(jdbc.queryForObject("SELECT consumed_at IS NULL FROM workforce.staff_provisioning_intents WHERE intent_id = ?", Boolean.class, issued.intentId())).isTrue();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM users.users", Integer.class)).isEqualTo(beforeUsers);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM workforce.staff_profiles WHERE tenant_id = ?", Integer.class, fixture.tenant())).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM access_control.role_definitions WHERE tenant_id = ?", Integer.class, fixture.tenant())).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM users.tenant_memberships WHERE tenant_id = ?", Integer.class, fixture.tenant())).isZero();
        } finally {
            jdbc.execute("ALTER TABLE access_control.staff_provisioning_role_events DROP CONSTRAINT ck_synthetic_cold_audit_failure");
        }
        assertThat(consumption.consume(issued.credential(), "https://synthetic-cold-start.test", UUID.randomUUID().toString())).isNotNull();
    }

    @Test
    void normalProvisioningCannotInferColdStartFromAPlatformActor() throws Exception {
        var fixture = fixture(true);
        var coldProof = issue(fixture);
        var command = jdbc.queryForObject("SELECT department_id, position_id, initial_role_code FROM workforce.staff_provisioning_intents WHERE intent_id = ?",
                (row, index) -> new IssueStaffProvisioningIntentCommand(fixture.tenant(), fixture.actor(), row.getObject("department_id", UUID.class),
                        row.getObject("position_id", UUID.class), row.getString("initial_role_code"), UUID.randomUUID(), UUID.randomUUID()), coldProof.intentId());
        var normalProof = (StaffProvisioningIssuance.Issued) primitive.issue(command);
        assertThatThrownBy(() -> consumption.consume(normalProof.credential(), "https://synthetic-cold-start.test", UUID.randomUUID().toString()))
                .isInstanceOf(StaffProvisioningUnavailableException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM workforce.staff_profiles WHERE tenant_id = ?", Integer.class, fixture.tenant())).isZero();
    }

    @Test
    void platformCancellationIsRestrictedToUnusedColdStart() throws Exception {
        var fixture = fixture(true);
        var cancelled = issue(fixture);
        assertThat(cancel(fixture, cancelled.intentId())).isTrue();
        assertThat(cancel(fixture, cancelled.intentId())).isFalse();
        assertThatThrownBy(() -> consumption.consume(cancelled.credential(), "https://synthetic-cold-start.test", UUID.randomUUID().toString()))
                .isInstanceOf(StaffProvisioningUnavailableException.class);
        var next = issue(fixture);
        var spare = issue(fixture);
        var staffId = consumption.consume(next.credential(), "https://synthetic-cold-start.test", UUID.randomUUID().toString());
        assertThatThrownBy(() -> cancel(fixture, spare.intentId())).isInstanceOf(StaffProvisioningUnavailableException.class);
        assertThat(jdbc.queryForObject("SELECT cancelled_at IS NULL FROM workforce.staff_provisioning_intents WHERE intent_id = ?", Boolean.class, spare.intentId())).isTrue();
        var userId = jdbc.queryForObject("SELECT user_id FROM workforce.staff_profiles WHERE staff_id = ?", UUID.class, staffId);
        assertThat(management.cancel(userId, fixture.tenant(), spare.intentId(), UUID.randomUUID())).isTrue();
    }

    private boolean cancel(Fixture fixture, UUID intentId) throws Exception {
        return coldStart.cancel(fixture.actor(), fixture.tenant(), intentId, UUID.randomUUID());
    }

    @Test
    void issuanceAuditFailureRollsBackPreparedMetadataAndProof() {
        var fixture = fixture(true);
        var operation = UUID.randomUUID();
        var correlation = UUID.randomUUID();
        jdbc.execute("ALTER TABLE workforce.provisioning_events ADD CONSTRAINT ck_synthetic_cold_issue_failure CHECK (correlation_id <> '" + correlation + "'::uuid)");
        try {
            assertThatThrownBy(() -> coldStart.issue(fixture.actor(), fixture.tenant(), operation, correlation))
                    .isInstanceOf(io.github.piresrenan.orderhub.workforce.application.port.out.StaffProvisioningIntentPersistenceException.class);
            assertNoPreparedMetadata(fixture);
        } finally {
            jdbc.execute("ALTER TABLE workforce.provisioning_events DROP CONSTRAINT ck_synthetic_cold_issue_failure");
        }
        var issued = coldStart.issue(fixture.actor(), fixture.tenant(), operation, correlation);
        assertThat(issued).isInstanceOf(StaffProvisioningIssuance.Issued.class);
        assertThat(coldStart.issue(fixture.actor(), fixture.tenant(), operation, correlation))
                .isEqualTo(new StaffProvisioningIssuance.Replay(((StaffProvisioningIssuance.Issued) issued).intentId()));
    }

    @Test
    void platformGrantRevocationWaitsForTheActualOuterTransaction() throws Exception {
        var fixture = fixture(true);
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        var issued = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var revokerPid = new java.util.concurrent.ArrayBlockingQueue<Integer>(1);
        try {
            var owner = executor.submit(() -> new org.springframework.transaction.support.TransactionTemplate(transactionManager).execute(status -> {
                var proof = issue(fixture);
                issued.countDown();
                try { assertThat(release.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue(); }
                catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw new AssertionError(exception); }
                return proof;
            }));
            assertThat(issued.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            var revoker = executor.submit(() -> new org.springframework.transaction.support.TransactionTemplate(transactionManager).execute(status -> {
                revokerPid.add(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                return jdbc.update("DELETE FROM access_control.administrative_grants WHERE user_id = ?", fixture.actor());
            }));
            var pid = revokerPid.poll(5, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(pid).isNotNull();
            org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(5)).untilAsserted(() ->
                    assertThat(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM pg_locks WHERE pid = ? AND NOT granted)", Boolean.class, pid)).isTrue());
            release.countDown();
            var proof = owner.get(5, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(revoker.get(5, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(1);
            assertThatThrownBy(() -> consumption.consume(proof.credential(), "https://synthetic-cold-start.test", UUID.randomUUID().toString()))
                    .isInstanceOf(StaffProvisioningAuthorizationDeniedException.class);
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(15, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void coldConsumptionAndCancellationHaveExactlyOneTerminalEffect() throws Exception {
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 32; round++) {
                var fixture = fixture(true);
                var proof = issue(fixture);
                var start = new java.util.concurrent.CountDownLatch(1);
                var ready = new java.util.concurrent.CountDownLatch(2);
                var consumer = executor.submit(() -> {
                    ready.countDown();
                    assertThat(start.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                    try {
                        consumption.consume(proof.credential(), "https://synthetic-cold-start.test", UUID.randomUUID().toString());
                        return true;
                    } catch (StaffProvisioningUnavailableException exception) { return false; }
                });
                var cancellation = executor.submit(() -> {
                    ready.countDown();
                    assertThat(start.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                    try { return cancel(fixture, proof.intentId()); }
                    catch (StaffProvisioningUnavailableException exception) { return false; }
                });
                assertThat(ready.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                start.countDown();
                assertThat((consumer.get(20, java.util.concurrent.TimeUnit.SECONDS) ? 1 : 0)
                        + (cancellation.get(20, java.util.concurrent.TimeUnit.SECONDS) ? 1 : 0)).isEqualTo(1);
                assertThat(jdbc.queryForObject("SELECT (consumed_at IS NOT NULL) <> (cancelled_at IS NOT NULL) FROM workforce.staff_provisioning_intents WHERE intent_id = ?",
                        Boolean.class, proof.intentId())).isTrue();
                assertThat(jdbc.queryForObject("SELECT count(*) FROM workforce.provisioning_events WHERE intent_id = ? AND action IN ('CONSUMED', 'CANCELLED')",
                        Integer.class, proof.intentId())).isEqualTo(1);
            }
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(20, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void changedPreparedCeilingCannotBeSilentlyClippedOrRepaired() {
        var fixture = fixture(true);
        var proof = issue(fixture);
        var position = jdbc.queryForObject("SELECT position_id FROM workforce.staff_provisioning_intents WHERE intent_id = ?", UUID.class, proof.intentId());
        jdbc.update("DELETE FROM workforce.job_position_permissions WHERE tenant_id = ? AND position_id = ? AND permission_code = 'INVENTORY_VIEW'", fixture.tenant(), position);
        var beforeUsers = jdbc.queryForObject("SELECT count(*) FROM users.users", Integer.class);
        assertThatThrownBy(() -> consumption.consume(proof.credential(), "https://synthetic-cold-start.test", UUID.randomUUID().toString()))
                .isInstanceOf(StaffProvisioningUnavailableException.class);
        assertThat(jdbc.queryForObject("SELECT consumed_at IS NULL FROM workforce.staff_provisioning_intents WHERE intent_id = ?", Boolean.class, proof.intentId())).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users.users", Integer.class)).isEqualTo(beforeUsers);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM workforce.job_position_permissions WHERE tenant_id = ? AND position_id = ? AND permission_code = 'INVENTORY_VIEW'",
                Integer.class, fixture.tenant(), position)).isZero();
    }

    private void assertNoPreparedMetadata(Fixture fixture) {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM workforce.departments WHERE tenant_id = ?", Integer.class, fixture.tenant())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM workforce.job_positions WHERE tenant_id = ?", Integer.class, fixture.tenant())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM access_control.role_definitions WHERE tenant_id = ?", Integer.class, fixture.tenant())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM workforce.staff_provisioning_intents WHERE tenant_id = ?", Integer.class, fixture.tenant())).isZero();
    }

    private StaffProvisioningIssuance.Issued issue(Fixture fixture) {
        return (StaffProvisioningIssuance.Issued) coldStart.issue(fixture.actor(), fixture.tenant(), UUID.randomUUID(), UUID.randomUUID());
    }

    private Fixture fixture(boolean platformManager) {
        var actor = users.resolveOrCreate(new ResolveExternalIdentityQuery("https://synthetic-cold-start.test", UUID.randomUUID().toString())).userId();
        var tenant = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants.tenants (id, name, status) VALUES (?, 'Synthetic cold start', 'ACTIVE')", tenant);
        if (platformManager) {
            jdbc.update("INSERT INTO access_control.administrative_grants (grant_id, user_id, scope_type, scope_id, permission_code) VALUES (?, ?, 'PLATFORM', NULL, 'PLATFORM_TENANTS_MANAGE')",
                    UUID.randomUUID(), actor);
        }
        return new Fixture(actor, tenant);
    }

    private record Fixture(UUID actor, UUID tenant) {}
}
