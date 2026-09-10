package io.github.piresrenan.orderhub.customers.application;

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
import io.github.piresrenan.orderhub.workforce.application.port.in.ColdStartStaffProvisioningUseCase;
import io.github.piresrenan.orderhub.workforce.application.port.in.ConsumeStaffProvisioningUseCase;
import io.github.piresrenan.orderhub.workforce.application.model.StaffProvisioningIssuance;
import io.github.piresrenan.orderhub.customers.application.port.in.linking.CustomerAccountLinkingUseCase;
import io.github.piresrenan.orderhub.customers.application.port.in.linking.CustomerLinkIssuance;
import io.github.piresrenan.orderhub.customers.application.port.in.linking.CustomerLinkUnavailableException;
import io.github.piresrenan.orderhub.customers.application.port.out.CustomerAccountBindingPersistenceException;

@SpringBootTest
@Import(PostgreSqlTestConfiguration.class)
class CustomerAccountLinkingProductionTest {
    @Autowired private CustomerAccountLinkingUseCase api;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ResolveOrCreateExternalUserUseCase users;
    @Autowired private ColdStartStaffProvisioningUseCase coldStart;
    @Autowired private ConsumeStaffProvisioningUseCase staffConsumption;
    @Autowired private org.springframework.transaction.PlatformTransactionManager transactionManager;

    @Test void authorizedProofLinksTrustedUserWithoutCreatingStaff() {
        var f = fixture();
        var issued = issue(f);
        var credential = issued.credential();
        var user = user();
        var customer = api.consume(user, f.tenant(), credential);
        assertThat(customer).isEqualTo(f.customer());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM customers.customer_account_bindings WHERE tenant_id = ? AND customer_id = ? AND user_id = ?",
                Integer.class, f.tenant(), f.customer(), user)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM workforce.staff_profiles WHERE user_id = ?", Integer.class, user)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM access_control.role_assignments WHERE user_id = ?", Integer.class, user)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM customers.account_link_events WHERE tenant_id = ?", Integer.class, f.tenant())).isEqualTo(2);
        assertThatThrownBy(() -> api.consume(user, f.tenant(), credential)).isInstanceOf(CustomerLinkUnavailableException.class);
        assertThat(issued.toString()).doesNotContain(credential);
    }

    @Test void uuidKnowledgeAndAuthorityInAnotherTenantCannotIssueOrConsume() {
        var f = fixture();
        var elsewhere = fixture();
        var stranger = user();
        assertThatThrownBy(() -> api.issue(stranger, f.tenant(), f.customer(), UUID.randomUUID(), UUID.randomUUID()))
                .isInstanceOf(CustomerLinkUnavailableException.class);
        assertThatThrownBy(() -> api.issue(elsewhere.manager(), f.tenant(), f.customer(), UUID.randomUUID(), UUID.randomUUID()))
                .isInstanceOf(CustomerLinkUnavailableException.class);
        assertThatThrownBy(() -> api.issue(f.manager(), f.tenant(), elsewhere.customer(), UUID.randomUUID(), UUID.randomUUID()))
                .isInstanceOf(CustomerLinkUnavailableException.class);
        var issued = issue(f);
        for (var invalid : java.util.List.of(f.customer().toString(), "a".repeat(43), issued.credential() + "=", "")) {
            assertThatThrownBy(() -> api.consume(stranger, f.tenant(), invalid)).isInstanceOf(CustomerLinkUnavailableException.class);
        }
        assertThatThrownBy(() -> api.consume(stranger, elsewhere.tenant(), issued.credential())).isInstanceOf(CustomerLinkUnavailableException.class);
        assertPending(issued);
        assertThat(api.consume(stranger, f.tenant(), issued.credential())).isEqualTo(f.customer());
    }

    @Test void operationReplayCannotRecoverSecretOrSwitchCustomer() {
        var f = fixture();
        var operation = UUID.randomUUID();
        var issued = (CustomerLinkIssuance.Issued) api.issue(f.manager(), f.tenant(), f.customer(), operation, UUID.randomUUID());
        assertThat(api.issue(f.manager(), f.tenant(), f.customer(), operation, UUID.randomUUID()))
                .isEqualTo(new CustomerLinkIssuance.Replay(issued.proofId()));
        var other = UUID.randomUUID();
        jdbc.update("INSERT INTO customers.customer_profiles VALUES (?, ?)", f.tenant(), other);
        assertThatThrownBy(() -> api.issue(f.manager(), f.tenant(), other, operation, UUID.randomUUID()))
                .isInstanceOf(CustomerLinkUnavailableException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM customers.account_link_events WHERE proof_id = ?", Integer.class, issued.proofId())).isEqualTo(1);
    }

    @Test void independentProofsPreserveExactTupleCardinalityAndExistingOwners() {
        var f = fixture();
        var one = user();
        var two = user();
        api.consume(one, f.tenant(), issue(f).credential());
        api.consume(one, f.tenant(), issue(f).credential());
        api.consume(two, f.tenant(), issue(f).credential());
        assertThat(jdbc.queryForList("SELECT user_id FROM customers.customer_account_bindings WHERE tenant_id = ? AND customer_id = ?",
                UUID.class, f.tenant(), f.customer())).containsExactlyInAnyOrder(one, two);
    }

    @Test void suspendedMembershipCannotBeReactivatedByAProof() {
        var f = fixture();
        var user = user();
        jdbc.update("INSERT INTO users.tenant_memberships (user_id, tenant_id, status) VALUES (?, ?, 'SUSPENDED')", user, f.tenant());
        var issued = issue(f);
        assertThatThrownBy(() -> api.consume(user, f.tenant(), issued.credential())).isInstanceOf(CustomerLinkUnavailableException.class);
        assertPending(issued);
        assertThat(jdbc.queryForObject("SELECT status FROM users.tenant_memberships WHERE user_id = ? AND tenant_id = ?", String.class, user, f.tenant())).isEqualTo("SUSPENDED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM customers.customer_account_bindings WHERE tenant_id = ?", Integer.class, f.tenant())).isZero();
    }

    @Test void revokedIssuerCannotAuthorizeLaterConsumption() {
        var f = fixture();
        var issued = issue(f);
        jdbc.update("UPDATE users.tenant_memberships SET status = 'TERMINATED' WHERE user_id = ? AND tenant_id = ?", f.manager(), f.tenant());
        assertThatThrownBy(() -> api.consume(user(), f.tenant(), issued.credential())).isInstanceOf(CustomerLinkUnavailableException.class);
        assertPending(issued);
    }

    @Test void auditFailureRollsBackBindingMembershipAndProofAndAllowsRetry() {
        var f = fixture();
        var issued = issue(f);
        var user = user();
        jdbc.execute("ALTER TABLE customers.account_link_events ADD CONSTRAINT synthetic_customer_audit_failure CHECK (action <> 'CONSUMED') NOT VALID");
        try {
            assertThatThrownBy(() -> api.consume(user, f.tenant(), issued.credential())).isInstanceOf(CustomerAccountBindingPersistenceException.class);
            assertPending(issued);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM customers.customer_account_bindings WHERE user_id = ?", Integer.class, user)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM users.tenant_memberships WHERE user_id = ?", Integer.class, user)).isZero();
        } finally { jdbc.execute("ALTER TABLE customers.account_link_events DROP CONSTRAINT synthetic_customer_audit_failure"); }
        assertThat(api.consume(user, f.tenant(), issued.credential())).isEqualTo(f.customer());
    }

    @Test void issuanceAndCancellationEvidenceFailuresRollBackTheirMutations() {
        var f = fixture();
        jdbc.execute("ALTER TABLE customers.account_link_events ADD CONSTRAINT synthetic_customer_issue_failure CHECK (action <> 'ISSUED') NOT VALID");
        try {
            assertThatThrownBy(() -> issue(f)).isInstanceOf(CustomerAccountBindingPersistenceException.class);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM customers.account_link_proofs WHERE tenant_id = ?", Integer.class, f.tenant())).isZero();
        } finally { jdbc.execute("ALTER TABLE customers.account_link_events DROP CONSTRAINT synthetic_customer_issue_failure"); }
        var issued = issue(f);
        jdbc.execute("ALTER TABLE customers.account_link_events ADD CONSTRAINT synthetic_customer_cancel_failure CHECK (action <> 'CANCELLED') NOT VALID");
        try {
            assertThatThrownBy(() -> api.cancel(f.manager(), f.tenant(), issued.proofId(), UUID.randomUUID())).isInstanceOf(CustomerAccountBindingPersistenceException.class);
            assertPending(issued);
        } finally { jdbc.execute("ALTER TABLE customers.account_link_events DROP CONSTRAINT synthetic_customer_cancel_failure"); }
        assertThat(api.cancel(f.manager(), f.tenant(), issued.proofId(), UUID.randomUUID())).isTrue();
        assertThat(api.cancel(f.manager(), f.tenant(), issued.proofId(), UUID.randomUUID())).isFalse();
        assertThatThrownBy(() -> api.consume(user(), f.tenant(), issued.credential())).isInstanceOf(CustomerLinkUnavailableException.class);
    }

    @Test void sameProofConcurrentClaimsHaveExactlyOneWinnerAcross32Rounds() throws Exception {
        var f = fixture();
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 32; round++) {
                var issued = issue(f);
                var one = user(); var two = user();
                var ready = new java.util.concurrent.CountDownLatch(2);
                var start = new java.util.concurrent.CountDownLatch(1);
                var first = executor.submit(() -> claim(f, issued, one, ready, start));
                var second = executor.submit(() -> claim(f, issued, two, ready, start));
                assertThat(ready.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue(); start.countDown();
                assertThat((first.get(20, java.util.concurrent.TimeUnit.SECONDS) ? 1 : 0)
                        + (second.get(20, java.util.concurrent.TimeUnit.SECONDS) ? 1 : 0)).isEqualTo(1);
                assertThat(jdbc.queryForObject("SELECT count(*) FROM customers.customer_account_bindings WHERE tenant_id = ? AND user_id IN (?, ?)",
                        Integer.class, f.tenant(), one, two)).isEqualTo(1);
                assertThat(jdbc.queryForObject("SELECT count(*) FROM customers.account_link_events WHERE proof_id = ? AND action = 'CONSUMED'", Integer.class, issued.proofId())).isEqualTo(1);
            }
        } finally { executor.shutdownNow(); assertThat(executor.awaitTermination(20, java.util.concurrent.TimeUnit.SECONDS)).isTrue(); }
    }

    private boolean claim(Fixture f, CustomerLinkIssuance.Issued issued, UUID user,
            java.util.concurrent.CountDownLatch ready, java.util.concurrent.CountDownLatch start) throws Exception {
        ready.countDown(); assertThat(start.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        try { api.consume(user, f.tenant(), issued.credential()); return true; }
        catch (CustomerLinkUnavailableException exception) { return false; }
    }

    @Test void consumptionAndCancellationHaveOneTerminalEffectAcross32Rounds() throws Exception {
        var f = fixture();
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 32; round++) {
                var issued = issue(f); var user = user();
                var ready = new java.util.concurrent.CountDownLatch(2);
                var start = new java.util.concurrent.CountDownLatch(1);
                var consumer = executor.submit(() -> claim(f, issued, user, ready, start));
                var cancellation = executor.submit(() -> {
                    ready.countDown(); assertThat(start.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                    return api.cancel(f.manager(), f.tenant(), issued.proofId(), UUID.randomUUID());
                });
                assertThat(ready.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue(); start.countDown();
                assertThat((consumer.get(20, java.util.concurrent.TimeUnit.SECONDS) ? 1 : 0)
                        + (cancellation.get(20, java.util.concurrent.TimeUnit.SECONDS) ? 1 : 0)).isEqualTo(1);
                assertThat(jdbc.queryForObject("SELECT (consumed_at IS NOT NULL) <> (cancelled_at IS NOT NULL) FROM customers.account_link_proofs WHERE proof_id = ?",
                        Boolean.class, issued.proofId())).isTrue();
                assertThat(jdbc.queryForObject("SELECT count(*) FROM customers.account_link_events WHERE proof_id = ? AND action IN ('CONSUMED', 'CANCELLED')",
                        Integer.class, issued.proofId())).isEqualTo(1);
            }
        } finally { executor.shutdownNow(); assertThat(executor.awaitTermination(20, java.util.concurrent.TimeUnit.SECONDS)).isTrue(); }
    }

    @Test void expiryDuringObservedDatabaseLockWaitPreventsAnyLink() throws Exception {
        var f = fixture(); var issued = issue(f); var user = user();
        jdbc.update("UPDATE customers.account_link_proofs SET expires_at = clock_timestamp() + interval '3 seconds' WHERE proof_id = ?", issued.proofId());
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        var locked = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var pidQueue = new java.util.concurrent.ArrayBlockingQueue<Integer>(1);
        try {
            var owner = executor.submit(() -> new org.springframework.transaction.support.TransactionTemplate(transactionManager).execute(status -> {
                jdbc.queryForObject("SELECT proof_id FROM customers.account_link_proofs WHERE proof_id = ? FOR UPDATE", UUID.class, issued.proofId());
                locked.countDown();
                try { assertThat(release.await(12, java.util.concurrent.TimeUnit.SECONDS)).isTrue(); }
                catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw new AssertionError(exception); }
                return true;
            }));
            assertThat(locked.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            var consumer = executor.submit(() -> new org.springframework.transaction.support.TransactionTemplate(transactionManager).execute(status -> {
                pidQueue.add(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                return api.consume(user, f.tenant(), issued.credential());
            }));
            var pid = pidQueue.poll(5, java.util.concurrent.TimeUnit.SECONDS); assertThat(pid).isNotNull();
            org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(2)).untilAsserted(() ->
                    assertThat(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM pg_locks WHERE pid = ? AND NOT granted)", Boolean.class, pid)).isTrue());
            assertThat(jdbc.queryForObject("SELECT clock_timestamp() < expires_at FROM customers.account_link_proofs WHERE proof_id = ?", Boolean.class, issued.proofId())).isTrue();
            org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(5)).until(() ->
                    jdbc.queryForObject("SELECT clock_timestamp() >= expires_at FROM customers.account_link_proofs WHERE proof_id = ?", Boolean.class, issued.proofId()));
            release.countDown(); assertThat(owner.get(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> consumer.get(5, java.util.concurrent.TimeUnit.SECONDS)).hasRootCauseInstanceOf(CustomerLinkUnavailableException.class);
            assertPending(issued);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM customers.customer_account_bindings WHERE user_id = ?", Integer.class, user)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM users.tenant_memberships WHERE user_id = ?", Integer.class, user)).isZero();
        } finally { release.countDown(); executor.shutdownNow(); assertThat(executor.awaitTermination(15, java.util.concurrent.TimeUnit.SECONDS)).isTrue(); }
    }

    @Test void ownerAdapterRejectsAutocommitAndAnUnrelatedPhysicalTransaction() {
        var repository = new io.github.piresrenan.orderhub.customers.adapter.out.persistence.postgresql.PostgreSqlCustomerLinkProofRepository(jdbc);
        assertThatThrownBy(() -> repository.consume(UUID.randomUUID(), new byte[32])).isInstanceOf(IllegalStateException.class);
        var wrong = new JdbcTemplate(new org.springframework.jdbc.datasource.DriverManagerDataSource("jdbc:postgresql://127.0.0.1:1/unused", "synthetic", "synthetic"));
        var unrelated = new io.github.piresrenan.orderhub.customers.adapter.out.persistence.postgresql.PostgreSqlCustomerLinkProofRepository(wrong);
        new org.springframework.transaction.support.TransactionTemplate(transactionManager).executeWithoutResult(status ->
                assertThatThrownBy(() -> unrelated.consume(UUID.randomUUID(), new byte[32])).isInstanceOf(IllegalStateException.class));
    }

    @Test void concurrentIssuanceReturnsOneSecretAndOneReplayAcross32Rounds() throws Exception {
        var f = fixture(); var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 32; round++) {
                var operation = UUID.randomUUID();
                var barrier = new java.util.concurrent.CyclicBarrier(2);
                java.util.concurrent.Callable<CustomerLinkIssuance> action = () -> {
                    barrier.await(10, java.util.concurrent.TimeUnit.SECONDS);
                    return api.issue(f.manager(), f.tenant(), f.customer(), operation, UUID.randomUUID());
                };
                var first = executor.submit(action); var second = executor.submit(action);
                var results = java.util.List.of(first.get(20, java.util.concurrent.TimeUnit.SECONDS), second.get(20, java.util.concurrent.TimeUnit.SECONDS));
                assertThat(results.stream().filter(CustomerLinkIssuance.Issued.class::isInstance).count()).isEqualTo(1);
                assertThat(results.stream().filter(CustomerLinkIssuance.Replay.class::isInstance).count()).isEqualTo(1);
                var issued = (CustomerLinkIssuance.Issued) results.stream().filter(CustomerLinkIssuance.Issued.class::isInstance).findFirst().orElseThrow();
                var replay = (CustomerLinkIssuance.Replay) results.stream().filter(CustomerLinkIssuance.Replay.class::isInstance).findFirst().orElseThrow();
                assertThat(replay.proofId()).isEqualTo(issued.proofId());
                assertThat(jdbc.queryForObject("SELECT credential_digest FROM customers.account_link_proofs WHERE proof_id = ?", byte[].class, issued.proofId()))
                        .isEqualTo(java.security.MessageDigest.getInstance("SHA-256").digest(java.util.Base64.getUrlDecoder().decode(issued.credential())));
                assertThat(jdbc.queryForObject("SELECT count(*) FROM customers.account_link_events WHERE proof_id = ?", Integer.class, issued.proofId())).isEqualTo(1);
            }
        } finally { executor.shutdownNow(); assertThat(executor.awaitTermination(20, java.util.concurrent.TimeUnit.SECONDS)).isTrue(); }
    }

    @Test void independentConcurrentProofsForSameUserConvergeOnOneBindingAcross32Rounds() throws Exception {
        var f = fixture(); var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 32; round++) {
                var one = issue(f); var two = issue(f); var user = user();
                var ready = new java.util.concurrent.CountDownLatch(2); var start = new java.util.concurrent.CountDownLatch(1);
                var first = executor.submit(() -> claim(f, one, user, ready, start));
                var second = executor.submit(() -> claim(f, two, user, ready, start));
                assertThat(ready.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue(); start.countDown();
                assertThat(first.get(20, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                assertThat(second.get(20, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                assertThat(jdbc.queryForObject("SELECT count(*) FROM customers.customer_account_bindings WHERE user_id = ? AND tenant_id = ?", Integer.class, user, f.tenant())).isEqualTo(1);
                assertThat(jdbc.queryForObject("SELECT count(*) FROM customers.account_link_events WHERE subject_user_id = ? AND action = 'CONSUMED'", Integer.class, user)).isEqualTo(2);
            }
        } finally { executor.shutdownNow(); assertThat(executor.awaitTermination(20, java.util.concurrent.TimeUnit.SECONDS)).isTrue(); }
    }

    private CustomerLinkIssuance.Issued issue(Fixture f) {
        return (CustomerLinkIssuance.Issued) api.issue(f.manager(), f.tenant(), f.customer(), UUID.randomUUID(), UUID.randomUUID());
    }
    private void assertPending(CustomerLinkIssuance.Issued issued) {
        assertThat(jdbc.queryForObject("SELECT consumed_at IS NULL AND cancelled_at IS NULL FROM customers.account_link_proofs WHERE proof_id = ?", Boolean.class, issued.proofId())).isTrue();
    }

    private UUID user() {
        return users.resolveOrCreate(new ResolveExternalIdentityQuery("https://synthetic-customer-link.test", UUID.randomUUID().toString())).userId();
    }

    private Fixture fixture() {
        var platform = user();
        var tenant = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants.tenants (id, name, status) VALUES (?, 'Synthetic customer link', 'ACTIVE')", tenant);
        jdbc.update("INSERT INTO access_control.administrative_grants (grant_id, user_id, scope_type, scope_id, permission_code) VALUES (?, ?, 'PLATFORM', NULL, 'PLATFORM_TENANTS_MANAGE')", UUID.randomUUID(), platform);
        var bootstrap = (StaffProvisioningIssuance.Issued) coldStart.issue(platform, tenant, UUID.randomUUID(), UUID.randomUUID());
        var staff = staffConsumption.consume(bootstrap.credential(), "https://synthetic-customer-link.test", UUID.randomUUID().toString());
        var manager = jdbc.queryForObject("SELECT user_id FROM workforce.staff_profiles WHERE tenant_id = ? AND staff_id = ?", UUID.class, tenant, staff);
        var customer = UUID.randomUUID();
        jdbc.update("INSERT INTO customers.customer_profiles VALUES (?, ?)", tenant, customer);
        return new Fixture(tenant, customer, manager);
    }
    private record Fixture(UUID tenant, UUID customer, UUID manager) {}
}
