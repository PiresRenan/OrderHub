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
import io.github.piresrenan.orderhub.users.application.port.in.EnsureActiveTenantMembershipUseCase;
import io.github.piresrenan.orderhub.users.application.port.in.EstablishTenantMembershipCommand;
import io.github.piresrenan.orderhub.users.application.port.in.TenantMembershipEnsureResult;
import io.github.piresrenan.orderhub.security.application.model.AuthenticatedUserPrincipal;
import io.github.piresrenan.orderhub.security.application.port.in.ResolveTrustedTenantContextUseCase;
import io.github.piresrenan.orderhub.security.application.port.in.ResolveTrustedTenantContextQuery;
import io.github.piresrenan.orderhub.workforce.application.port.in.ColdStartStaffProvisioningUseCase;
import io.github.piresrenan.orderhub.workforce.application.port.in.ConsumeStaffProvisioningUseCase;
import io.github.piresrenan.orderhub.workforce.application.model.StaffProvisioningIssuance;
import io.github.piresrenan.orderhub.customers.application.port.in.linking.CustomerAccountLinkingUseCase;
import io.github.piresrenan.orderhub.customers.application.port.in.linking.CustomerLinkIssuance;

/**
 * Why: membership changes must affect future access without erasing business identity.
 * Covers: authorized transitions, recovery/termination and administrator races, snapshots and audit rollback.
 * Prevents: implicit reactivation, mutual access removal and an ambient transaction reusing a terminated actor.
 */
@SpringBootTest
@Import(PostgreSqlTestConfiguration.class)
class TenantMembershipAdministrationProductionTest {
    @Autowired private io.github.piresrenan.orderhub.workforce.application.port.in.ManageTenantMembershipUseCase administration;
    @Autowired private io.github.piresrenan.orderhub.workforce.application.port.in.ManageStaffProvisioningUseCase staffIssuance;
    @Autowired private org.springframework.transaction.PlatformTransactionManager transactionManager;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ResolveOrCreateExternalUserUseCase users;
    @Autowired private EnsureActiveTenantMembershipUseCase ensureMembership;
    @Autowired private ResolveTrustedTenantContextUseCase trustedContext;
    @Autowired private ColdStartStaffProvisioningUseCase coldStart;
    @Autowired private ConsumeStaffProvisioningUseCase staffConsumption;
    @Autowired private CustomerAccountLinkingUseCase customers;

    @Test void suspendRecoverAndTerminateChangeFutureContextWithoutErasingCustomerHistory() throws Exception {
        var api = api(); var fixture = fixture();
        var target = user(); var customer = UUID.randomUUID();
        jdbc.update("INSERT INTO customers.customer_profiles VALUES (?, ?)", fixture.tenant(), customer);
        var proof = (CustomerLinkIssuance.Issued) customers.issue(fixture.manager(), fixture.tenant(), customer, UUID.randomUUID(), UUID.randomUUID());
        customers.consume(target, fixture.tenant(), proof.credential());
        var query = new ResolveTrustedTenantContextQuery(new AuthenticatedUserPrincipal(target), fixture.tenant());
        assertThat(trustedContext.resolve(query)).isPresent();
        assertThat(invoke(api, "suspend", fixture.manager(), fixture.tenant(), target)).isTrue();
        assertThat(trustedContext.resolve(query)).isEmpty();
        assertThat(ensureMembership.ensureActive(new EstablishTenantMembershipCommand(target, fixture.tenant())))
                .isInstanceOf(TenantMembershipEnsureResult.NonOperational.class);
        assertThat(invoke(api, "recover", fixture.manager(), fixture.tenant(), target)).isTrue();
        assertThat(trustedContext.resolve(query)).isPresent();
        assertThat(invoke(api, "terminate", fixture.manager(), fixture.tenant(), target)).isTrue();
        assertThat(trustedContext.resolve(query)).isEmpty();
        assertThatThrownBy(() -> invoke(api, "recover", fixture.manager(), fixture.tenant(), target))
                .isInstanceOf(RuntimeException.class).hasMessage("Tenant membership administration is unavailable");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM customers.customer_account_bindings WHERE tenant_id = ? AND user_id = ?", Integer.class, fixture.tenant(), target)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users.tenant_membership_events WHERE tenant_id = ? AND subject_user_id = ?", Integer.class, fixture.tenant(), target)).isEqualTo(3);
    }

    @Test void firstGovernanceActorCannotDisableItsOwnMembership() throws Exception {
        var api = api(); var fixture = fixture();
        assertThatThrownBy(() -> invoke(api, "terminate", fixture.manager(), fixture.tenant(), fixture.manager()))
                .isInstanceOf(RuntimeException.class).hasMessage("Tenant membership administration is unavailable");
        assertThat(jdbc.queryForObject("SELECT status FROM users.tenant_memberships WHERE user_id = ? AND tenant_id = ?", String.class, fixture.manager(), fixture.tenant())).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM workforce.staff_profiles WHERE user_id = ? AND tenant_id = ?", Integer.class, fixture.manager(), fixture.tenant())).isEqualTo(1);
    }

    @Test void evidenceFailureRollsBackEachTransitionAndAllowsRetry() {
        var fixture = fixture(); var target = member(fixture);
        for (var action : new String[]{"suspend", "recover", "terminate"}) {
            var before = status(fixture, target);
            jdbc.execute("ALTER TABLE users.tenant_membership_events ADD CONSTRAINT synthetic_membership_evidence_failure CHECK (FALSE) NOT VALID");
            try {
                assertThatThrownBy(() -> invoke(api(), action, fixture.manager(), fixture.tenant(), target))
                        .isInstanceOf(io.github.piresrenan.orderhub.users.application.port.out.TenantMembershipPersistenceException.class);
                assertThat(status(fixture, target)).isEqualTo(before);
            } finally { jdbc.execute("ALTER TABLE users.tenant_membership_events DROP CONSTRAINT synthetic_membership_evidence_failure"); }
            assertThat(invoke(api(), action, fixture.manager(), fixture.tenant(), target)).isTrue();
            assertThat(invoke(api(), action, fixture.manager(), fixture.tenant(), target)).isFalse();
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users.tenant_membership_events WHERE tenant_id = ? AND subject_user_id = ?", Integer.class, fixture.tenant(), target)).isEqualTo(3);
    }
    @Test void unauthorizedAndCrossTenantSelectorsHaveTheSameBoundedFailure() {
        var fixture = fixture(); var target = member(fixture); var stranger = user();
        for (var selected : new UUID[]{target, UUID.randomUUID()}) {
            assertThatThrownBy(() -> administration.suspend(stranger, fixture.tenant(), selected, UUID.randomUUID()))
                    .isInstanceOf(io.github.piresrenan.orderhub.workforce.application.port.in.TenantMembershipAdministrationUnavailableException.class)
                    .hasMessage("Tenant membership administration is unavailable");
        }
        assertThatThrownBy(() -> administration.suspend(fixture.manager(), UUID.randomUUID(), target, UUID.randomUUID()))
                .hasMessage("Tenant membership administration is unavailable");
        assertThat(status(fixture, target)).isEqualTo("ACTIVE");
    }
    @Test void committedSuspensionPreventsFutureContextWhileUncommittedStateDoesNotCancelPriorReads() throws Exception {
        var fixture = fixture(); var target = member(fixture);
        var query = new ResolveTrustedTenantContextQuery(new AuthenticatedUserPrincipal(target), fixture.tenant());
        var mutated = new java.util.concurrent.CountDownLatch(1); var release = new java.util.concurrent.CountDownLatch(1);
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            var operation = executor.submit(() -> new org.springframework.transaction.support.TransactionTemplate(transactionManager).execute(tx -> {
                var changed = administration.suspend(fixture.manager(), fixture.tenant(), target, UUID.randomUUID()); mutated.countDown();
                try { assertThat(release.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue(); }
                catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw new AssertionError(exception); }
                return changed;
            }));
            assertThat(mutated.await(8, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(trustedContext.resolve(query)).isPresent();
            release.countDown(); assertThat(operation.get(8, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(trustedContext.resolve(query)).isEmpty();
        } finally { release.countDown(); executor.shutdownNow(); assertThat(executor.awaitTermination(15, java.util.concurrent.TimeUnit.SECONDS)).isTrue(); }
    }
    @Test void outerRollbackPreservesMembershipAndEvidence() {
        var fixture = fixture(); var target = member(fixture);
        new org.springframework.transaction.support.TransactionTemplate(transactionManager).execute(tx -> {
            assertThat(administration.terminate(fixture.manager(), fixture.tenant(), target, UUID.randomUUID())).isTrue();
            tx.setRollbackOnly(); return null;
        });
        assertThat(status(fixture, target)).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users.tenant_membership_events WHERE tenant_id = ?", Integer.class, fixture.tenant())).isZero();
    }

    @Test void memberManagerAtLowerBandCannotDisableGovernanceButCanManageCustomerMembership() {
        var fixture = fixture(); var lower = secondGovernor(fixture); var customerUser = member(fixture);
        var position = UUID.randomUUID();
        jdbc.update("INSERT INTO workforce.job_positions (position_id, tenant_id, code, title, authority_band) VALUES (?, ?, ?, 'Synthetic membership manager', 'MANAGEMENT')", position, fixture.tenant(), "LOWER_" + position);
        jdbc.update("INSERT INTO workforce.job_position_permissions (tenant_id, position_id, permission_code) VALUES (?, ?, 'TENANT_MEMBERS_MANAGE')", fixture.tenant(), position);
        jdbc.update("UPDATE workforce.staff_placements SET position_id = ? WHERE tenant_id = ? AND staff_id = (SELECT staff_id FROM workforce.staff_profiles WHERE tenant_id = ? AND user_id = ?)", position, fixture.tenant(), fixture.tenant(), lower);
        assertThat(administration.suspend(lower, fixture.tenant(), customerUser, UUID.randomUUID())).isTrue();
        assertThatThrownBy(() -> administration.suspend(lower, fixture.tenant(), fixture.manager(), UUID.randomUUID()))
                .hasMessage("Tenant membership administration is unavailable");
        assertThat(status(fixture, fixture.manager())).isEqualTo("ACTIVE");
    }

    @Test void revokedActorMembershipCannotRecoverOthers() {
        var fixture = fixture(); var other = secondGovernor(fixture); var target = member(fixture);
        administration.suspend(fixture.manager(), fixture.tenant(), target, UUID.randomUUID());
        administration.suspend(fixture.manager(), fixture.tenant(), other, UUID.randomUUID());
        assertThatThrownBy(() -> administration.recover(other, fixture.tenant(), target, UUID.randomUUID()))
                .hasMessage("Tenant membership administration is unavailable");
        assertThat(status(fixture, target)).isEqualTo("SUSPENDED");
    }

    @Test void joinedTransactionCannotReuseAnActorItHasAlreadyTerminated() {
        var fixture = fixture(); var other = secondGovernor(fixture);
        new org.springframework.transaction.support.TransactionTemplate(transactionManager).execute(tx -> {
            assertThat(administration.terminate(fixture.manager(), fixture.tenant(), other, UUID.randomUUID())).isTrue();
            assertThatThrownBy(() -> administration.terminate(other, fixture.tenant(), fixture.manager(), UUID.randomUUID()))
                    .hasMessage("Tenant membership administration is unavailable");
            return null;
        });
        assertThat(status(fixture, fixture.manager())).isEqualTo("ACTIVE");
        assertThat(status(fixture, other)).isEqualTo("TERMINATED");
    }
    @Test void concurrentRecoveryAndTerminationNeverResurrectTerminalMembership() throws Exception {
        var fixture = fixture(); var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 32; round++) {
                var target = member(fixture); administration.suspend(fixture.manager(), fixture.tenant(), target, UUID.randomUUID());
                var barrier = new java.util.concurrent.CyclicBarrier(2);
                var first = executor.submit(() -> race("recover", fixture.manager(), fixture.tenant(), target, barrier));
                var second = executor.submit(() -> race("terminate", fixture.manager(), fixture.tenant(), target, barrier));
                first.get(20, java.util.concurrent.TimeUnit.SECONDS); assertThat(second.get(20, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                assertThat(status(fixture, target)).isEqualTo("TERMINATED");
            }
        } finally { executor.shutdownNow(); assertThat(executor.awaitTermination(20, java.util.concurrent.TimeUnit.SECONDS)).isTrue(); }
    }
    @Test void concurrentGovernanceActorsCannotDisableEachOther() throws Exception {
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 32; round++) {
                var fixture = fixture(); var other = secondGovernor(fixture);
                var barrier = new java.util.concurrent.CyclicBarrier(2);
                var first = executor.submit(() -> race("terminate", fixture.manager(), fixture.tenant(), other, barrier));
                var second = executor.submit(() -> race("terminate", other, fixture.tenant(), fixture.manager(), barrier));
                var a = first.get(20, java.util.concurrent.TimeUnit.SECONDS); var b = second.get(20, java.util.concurrent.TimeUnit.SECONDS);
                assertThat((a ? 1 : 0) + (b ? 1 : 0)).isEqualTo(1);
                assertThat(jdbc.queryForObject("SELECT count(*) FROM users.tenant_memberships WHERE tenant_id = ? AND status = 'ACTIVE'", Integer.class, fixture.tenant())).isEqualTo(1);
                assertThat(jdbc.queryForObject("SELECT count(*) FROM workforce.staff_profiles WHERE tenant_id = ?", Integer.class, fixture.tenant())).isEqualTo(2);
            }
        } finally { executor.shutdownNow(); assertThat(executor.awaitTermination(20, java.util.concurrent.TimeUnit.SECONDS)).isTrue(); }
    }
    private boolean race(String action, UUID actor, UUID tenant, UUID subject, java.util.concurrent.CyclicBarrier barrier) throws Exception {
        barrier.await(10, java.util.concurrent.TimeUnit.SECONDS);
        try { return invoke(api(), action, actor, tenant, subject); }
        catch (io.github.piresrenan.orderhub.workforce.application.port.in.TenantMembershipAdministrationUnavailableException exception) { return false; }
    }
    private UUID secondGovernor(Fixture fixture) {
        var placement = jdbc.queryForMap("SELECT department_id, position_id FROM workforce.staff_placements WHERE tenant_id = ?", fixture.tenant());
        var proof = (StaffProvisioningIssuance.Issued) staffIssuance.issue(new io.github.piresrenan.orderhub.workforce.application.model.IssueStaffProvisioningIntentCommand(
                fixture.tenant(), fixture.manager(), (UUID) placement.get("department_id"), (UUID) placement.get("position_id"),
                "INITIAL_TENANT_GOVERNANCE_V1", UUID.randomUUID(), UUID.randomUUID()));
        var staff = staffConsumption.consume(proof.credential(), "https://synthetic-membership-admin.test", UUID.randomUUID().toString());
        return jdbc.queryForObject("SELECT user_id FROM workforce.staff_profiles WHERE tenant_id = ? AND staff_id = ?", UUID.class, fixture.tenant(), staff);
    }
    private UUID member(Fixture fixture) {
        var target = user(); ensureMembership.ensureActive(new EstablishTenantMembershipCommand(target, fixture.tenant())); return target;
    }
    private String status(Fixture fixture, UUID subject) {
        return jdbc.queryForObject("SELECT status FROM users.tenant_memberships WHERE tenant_id = ? AND user_id = ?", String.class, fixture.tenant(), subject);
    }
    private io.github.piresrenan.orderhub.workforce.application.port.in.ManageTenantMembershipUseCase api() { return administration; }
    private boolean invoke(io.github.piresrenan.orderhub.workforce.application.port.in.ManageTenantMembershipUseCase api, String method, UUID actor, UUID tenant, UUID subject) {
        return switch (method) {
            case "suspend" -> api.suspend(actor, tenant, subject, UUID.randomUUID());
            case "recover" -> api.recover(actor, tenant, subject, UUID.randomUUID());
            case "terminate" -> api.terminate(actor, tenant, subject, UUID.randomUUID());
            default -> throw new IllegalArgumentException("Unknown test action");
        };
    }
    private UUID user() { return users.resolveOrCreate(new ResolveExternalIdentityQuery("https://synthetic-membership-admin.test", UUID.randomUUID().toString())).userId(); }
    private Fixture fixture() {
        var platform = user(); var tenant = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants.tenants (id, name, status) VALUES (?, 'Synthetic membership administration', 'ACTIVE')", tenant);
        jdbc.update("INSERT INTO access_control.administrative_grants (grant_id, user_id, scope_type, scope_id, permission_code) VALUES (?, ?, 'PLATFORM', NULL, 'PLATFORM_TENANTS_MANAGE')", UUID.randomUUID(), platform);
        var bootstrap = (StaffProvisioningIssuance.Issued) coldStart.issue(platform, tenant, UUID.randomUUID(), UUID.randomUUID());
        var staff = staffConsumption.consume(bootstrap.credential(), "https://synthetic-membership-admin.test", UUID.randomUUID().toString());
        var manager = jdbc.queryForObject("SELECT user_id FROM workforce.staff_profiles WHERE tenant_id = ? AND staff_id = ?", UUID.class, tenant, staff);
        return new Fixture(tenant, manager);
    }
    private record Fixture(UUID tenant, UUID manager) {}
}
