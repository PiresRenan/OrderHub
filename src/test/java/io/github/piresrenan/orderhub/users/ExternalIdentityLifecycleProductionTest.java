package io.github.piresrenan.orderhub.users;

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
import io.github.piresrenan.orderhub.users.application.port.in.ResolveExternalIdentityUseCase;
import io.github.piresrenan.orderhub.users.application.port.in.ResolveOrCreateExternalUserUseCase;
import io.github.piresrenan.orderhub.users.application.port.in.ExternalIdentityLifecycleUseCase;
import io.github.piresrenan.orderhub.users.application.port.in.ExternalIdentityLinkIssuance;
import io.github.piresrenan.orderhub.users.application.port.in.ExternalIdentityLifecycleUnavailableException;
import io.github.piresrenan.orderhub.users.application.port.in.BindExternalIdentityUseCase;
import io.github.piresrenan.orderhub.users.application.port.in.BindExternalIdentityCommand;
import io.github.piresrenan.orderhub.users.application.port.out.ExternalIdentityBindingPersistenceException;

/** Owner runtime tests use synthetic already-verified facts; real JWT verification is a separate boundary. */
/**
 * Why: An external provider identity must preserve one stable internal owner.
 * Covers: The binding lifecycle, schema or provider migration behavior exercised by this suite.
 * Prevents: Identity reassignment, orphan Users and history loss across retries or upgrades.
 */
@SpringBootTest(properties = {
        "orderhub.security.jwt.issuer=https://synthetic-identity-lifecycle.test",
        "orderhub.security.jwt.additional-issuers[0].issuer=https://synthetic-next-provider.test",
        "orderhub.security.jwt.additional-issuers[0].jwk-set-uri=http://127.0.0.1:1/unused-lifecycle-test-jwks"
})
@Import(PostgreSqlTestConfiguration.class)
class ExternalIdentityLifecycleProductionTest {
    @Autowired private ExternalIdentityLifecycleUseCase api;
    @Autowired private BindExternalIdentityUseCase bindings;
    @Autowired private org.springframework.transaction.PlatformTransactionManager manager;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ResolveOrCreateExternalUserUseCase provisioning;
    @Autowired private ResolveExternalIdentityUseCase resolution;

    @Test void verifiedAdditionalIdentityAndSafeUnlinkPreserveTheSameUser() throws Exception {
        var old = identity("old"); var next = identity("new");
        var user = provisioning.resolveOrCreate(old).userId();
        var proof = issue(user);
        assertThat(api.consume(proof, next.issuer(), next.subject())).isEqualTo(user);
        assertThat(resolution.resolve(next).orElseThrow().userId()).isEqualTo(user);
        var oldBinding = bindingId(old);
        assertThat(api.unlink(user, oldBinding, UUID.randomUUID())).isTrue();
        assertThat(resolution.resolve(old)).isEmpty();
        assertThat(resolution.resolve(next).orElseThrow().userId()).isEqualTo(user);
        assertThat(jdbc.queryForObject("SELECT user_id FROM users.external_identity_bindings WHERE binding_id = ?", UUID.class, oldBinding)).isEqualTo(user);
        var before = jdbc.queryForObject("SELECT count(*) FROM users.users", Integer.class);
        assertThatThrownBy(() -> provisioning.resolveOrCreate(old)).isInstanceOf(RuntimeException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users.users", Integer.class)).isEqualTo(before);
    }

    @Test void existingOwnershipCannotBeReassignedEvenWithAValidLinkProof() throws Exception {
        var mine = identity("mine"); var theirs = identity("theirs");
        var user = provisioning.resolveOrCreate(mine).userId();
        var owner = provisioning.resolveOrCreate(theirs).userId();
        var proof = issue(user);
        assertThatThrownBy(() -> api.consume(proof, theirs.issuer(), theirs.subject()))
                .isInstanceOf(RuntimeException.class).hasMessage("External identity lifecycle is unavailable");
        assertThat(resolution.resolve(theirs).orElseThrow().userId()).isEqualTo(owner);
        var fresh = identity("fresh");
        assertThat(api.consume(proof, fresh.issuer(), fresh.subject())).isEqualTo(user);
    }

    @Test void lastActiveBindingRemovalIsExplicitlyDenied() throws Exception {
        var original = identity("last"); var user = provisioning.resolveOrCreate(original).userId();
        var binding = bindingId(original);
        assertThatThrownBy(() -> api.unlink(user, binding, UUID.randomUUID()))
                .isInstanceOf(RuntimeException.class).hasMessage("External identity lifecycle is unavailable");
        assertThat(resolution.resolve(original).orElseThrow().userId()).isEqualTo(user);
    }

    private String issue(UUID user) {
        return ((ExternalIdentityLinkIssuance.Issued) api.issue(user, UUID.randomUUID(), UUID.randomUUID())).credential();
    }

    @Test void relinkRequiresNewProofAndRetainsTheOriginalBindingIdentity() {
        var old = identity("relink-old"); var next = identity("relink-next");
        var user = provisioning.resolveOrCreate(old).userId();
        api.consume(issue(user), next.issuer(), next.subject());
        var binding = bindingId(old);
        api.unlink(user, binding, UUID.randomUUID());
        var proof = issue(user);
        assertThat(api.consume(proof, old.issuer(), old.subject())).isEqualTo(user);
        assertThat(bindingId(old)).isEqualTo(binding);
        assertThat(resolution.resolve(old).orElseThrow().userId()).isEqualTo(user);
        assertThatThrownBy(() -> api.consume(proof, old.issuer(), old.subject())).isInstanceOf(ExternalIdentityLifecycleUnavailableException.class);
    }

    @Test void migrationToConfiguredProviderPreservesUserAndDoesNotCountRetiredProvidersAsUsable() {
        var old = identity("migration"); var user = provisioning.resolveOrCreate(old).userId();
        bindings.bind(new BindExternalIdentityCommand("https://retired-provider.test", UUID.randomUUID().toString(), user));
        assertThatThrownBy(() -> api.unlink(user, bindingId(old), UUID.randomUUID())).isInstanceOf(ExternalIdentityLifecycleUnavailableException.class);
        var next = new ResolveExternalIdentityQuery("https://synthetic-next-provider.test", UUID.randomUUID().toString());
        assertThat(api.consume(issue(user), next.issuer(), next.subject())).isEqualTo(user);
        assertThat(api.unlink(user, bindingId(old), UUID.randomUUID())).isTrue();
        assertThat(resolution.resolve(next).orElseThrow().userId()).isEqualTo(user);
        assertThatThrownBy(() -> api.unlink(user, bindingId(next), UUID.randomUUID())).isInstanceOf(ExternalIdentityLifecycleUnavailableException.class);
    }

    @Test void replayCancellationAndSelectorsNeverRecoverOrSubstituteForAProof() {
        var user = provisioning.resolveOrCreate(identity("proof")).userId();
        var other = provisioning.resolveOrCreate(identity("other")).userId();
        var operation = UUID.randomUUID();
        var issued = (ExternalIdentityLinkIssuance.Issued) api.issue(user, operation, UUID.randomUUID());
        assertThat(api.issue(user, operation, UUID.randomUUID())).isEqualTo(new ExternalIdentityLinkIssuance.Replay(issued.proofId()));
        assertThat(issued.toString()).doesNotContain(issued.credential());
        assertThat(api.cancel(other, issued.proofId(), UUID.randomUUID())).isFalse();
        assertThat(api.cancel(user, issued.proofId(), UUID.randomUUID())).isTrue();
        assertThat(api.cancel(user, issued.proofId(), UUID.randomUUID())).isFalse();
        var next = identity("rejected");
        for (var invalid : java.util.List.of(issued.credential(), user.toString(), "a".repeat(43), issued.credential() + "=")) {
            assertThatThrownBy(() -> api.consume(invalid, next.issuer(), next.subject())).isInstanceOf(ExternalIdentityLifecycleUnavailableException.class);
        }
        assertThat(resolution.resolve(next)).isEmpty();
        assertThat(api.accounts(other)).noneMatch(account -> api.accounts(user).contains(account));
    }

    @Test void requiredEvidenceFailureRollsBackLinkAndUnlinkAndAllowsRetry() {
        var old = identity("audit-old"); var next = identity("audit-next"); var user = provisioning.resolveOrCreate(old).userId();
        var proof = issue(user);
        jdbc.execute("ALTER TABLE users.external_identity_events ADD CONSTRAINT synthetic_link_audit_failure CHECK (action <> 'LINKED') NOT VALID");
        try {
            assertThatThrownBy(() -> api.consume(proof, next.issuer(), next.subject())).isInstanceOf(ExternalIdentityBindingPersistenceException.class);
            assertThat(resolution.resolve(next)).isEmpty();
        } finally { jdbc.execute("ALTER TABLE users.external_identity_events DROP CONSTRAINT synthetic_link_audit_failure"); }
        assertThat(api.consume(proof, next.issuer(), next.subject())).isEqualTo(user);
        jdbc.execute("ALTER TABLE users.external_identity_events ADD CONSTRAINT synthetic_unlink_audit_failure CHECK (action <> 'UNLINKED') NOT VALID");
        try {
            assertThatThrownBy(() -> api.unlink(user, bindingId(old), UUID.randomUUID())).isInstanceOf(ExternalIdentityBindingPersistenceException.class);
            assertThat(resolution.resolve(old).orElseThrow().userId()).isEqualTo(user);
        } finally { jdbc.execute("ALTER TABLE users.external_identity_events DROP CONSTRAINT synthetic_unlink_audit_failure"); }
        assertThat(api.unlink(user, bindingId(old), UUID.randomUUID())).isTrue();
    }

    @Test void competingOwnersCannotBothLinkTheSamePairAcross32Rounds() throws Exception {
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 32; round++) {
                var one = provisioning.resolveOrCreate(identity("one")).userId();
                var two = provisioning.resolveOrCreate(identity("two")).userId();
                var firstProof = issue(one); var secondProof = issue(two); var shared = identity("shared");
                var barrier = new java.util.concurrent.CyclicBarrier(2);
                var first = executor.submit(() -> raceLink(firstProof, shared, barrier));
                var second = executor.submit(() -> raceLink(secondProof, shared, barrier));
                var firstResult = first.get(20, java.util.concurrent.TimeUnit.SECONDS);
                var secondResult = second.get(20, java.util.concurrent.TimeUnit.SECONDS);
                assertThat((firstResult == null ? 0 : 1) + (secondResult == null ? 0 : 1)).isEqualTo(1);
                assertThat(resolution.resolve(shared).orElseThrow().userId()).isEqualTo(firstResult == null ? secondResult : firstResult);
                assertThat(jdbc.queryForObject("SELECT count(*) FROM users.external_identity_bindings WHERE issuer = ? AND subject = ?", Integer.class, shared.issuer(), shared.subject())).isEqualTo(1);
            }
        } finally { executor.shutdownNow(); assertThat(executor.awaitTermination(20, java.util.concurrent.TimeUnit.SECONDS)).isTrue(); }
    }

    private UUID raceLink(String proof, ResolveExternalIdentityQuery identity, java.util.concurrent.CyclicBarrier barrier) throws Exception {
        barrier.await(10, java.util.concurrent.TimeUnit.SECONDS);
        try { return api.consume(proof, identity.issuer(), identity.subject()); }
        catch (ExternalIdentityLifecycleUnavailableException exception) { return null; }
    }

    @Test void competingUnlinksNeverRemoveTheLastUsableBindingAcross32Rounds() throws Exception {
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 32; round++) {
                var old = identity("unlink-old"); var next = identity("unlink-next"); var user = provisioning.resolveOrCreate(old).userId();
                api.consume(issue(user), next.issuer(), next.subject());
                var firstId = bindingId(old); var secondId = bindingId(next);
                var barrier = new java.util.concurrent.CyclicBarrier(2);
                var first = executor.submit(() -> raceUnlink(user, firstId, barrier));
                var second = executor.submit(() -> raceUnlink(user, secondId, barrier));
                assertThat((first.get(20, java.util.concurrent.TimeUnit.SECONDS) ? 1 : 0) + (second.get(20, java.util.concurrent.TimeUnit.SECONDS) ? 1 : 0)).isEqualTo(1);
                assertThat(jdbc.queryForObject("SELECT count(*) FROM users.external_identity_bindings WHERE user_id = ? AND active", Integer.class, user)).isEqualTo(1);
                assertThat(jdbc.queryForObject("SELECT count(*) FROM users.external_identity_bindings WHERE user_id = ?", Integer.class, user)).isEqualTo(2);
            }
        } finally { executor.shutdownNow(); assertThat(executor.awaitTermination(20, java.util.concurrent.TimeUnit.SECONDS)).isTrue(); }
    }
    private boolean raceUnlink(UUID user, UUID binding, java.util.concurrent.CyclicBarrier barrier) throws Exception {
        barrier.await(10, java.util.concurrent.TimeUnit.SECONDS);
        try { return api.unlink(user, binding, UUID.randomUUID()); }
        catch (ExternalIdentityLifecycleUnavailableException exception) { return false; }
    }

    @Test void authenticationResolutionRejectsUnlinkedPairAfterCommitWithoutErasingHistory() throws Exception {
        var old = identity("authentication-old"); var next = identity("authentication-next"); var user = provisioning.resolveOrCreate(old).userId();
        api.consume(issue(user), next.issuer(), next.subject());
        var mutated = new java.util.concurrent.CountDownLatch(1); var release = new java.util.concurrent.CountDownLatch(1);
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            var unlink = executor.submit(() -> new org.springframework.transaction.support.TransactionTemplate(manager).execute(status -> {
                var result = api.unlink(user, bindingId(old), UUID.randomUUID()); mutated.countDown();
                try { assertThat(release.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue(); }
                catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw new AssertionError(exception); }
                return result;
            }));
            assertThat(mutated.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(resolution.resolve(old).orElseThrow().userId()).isEqualTo(user);
            release.countDown(); assertThat(unlink.get(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(resolution.resolve(old)).isEmpty();
            assertThat(resolution.resolve(next).orElseThrow().userId()).isEqualTo(user);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM users.external_identity_bindings WHERE user_id = ?", Integer.class, user)).isEqualTo(2);
        } finally { release.countDown(); executor.shutdownNow(); assertThat(executor.awaitTermination(15, java.util.concurrent.TimeUnit.SECONDS)).isTrue(); }
    }
    private UUID bindingId(ResolveExternalIdentityQuery identity) {
        return jdbc.queryForObject("SELECT binding_id FROM users.external_identity_bindings WHERE issuer = ? AND subject = ?", UUID.class, identity.issuer(), identity.subject());
    }

    @Test void sameProofCannotLinkTwoIdentitiesAcross32Rounds() throws Exception {
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 32; round++) {
                var user = provisioning.resolveOrCreate(identity("single-proof")).userId();
                var proof = issue(user); var one = identity("one-target"); var two = identity("two-target");
                var barrier = new java.util.concurrent.CyclicBarrier(2);
                var first = executor.submit(() -> raceLink(proof, one, barrier));
                var second = executor.submit(() -> raceLink(proof, two, barrier));
                assertThat((first.get(20, java.util.concurrent.TimeUnit.SECONDS) == null ? 0 : 1)
                        + (second.get(20, java.util.concurrent.TimeUnit.SECONDS) == null ? 0 : 1)).isEqualTo(1);
                assertThat(jdbc.queryForObject("SELECT count(*) FROM users.external_identity_bindings WHERE user_id = ?", Integer.class, user)).isEqualTo(2);
                assertThat(jdbc.queryForObject("SELECT count(*) FROM users.external_identity_events WHERE user_id = ? AND action = 'LINKED'", Integer.class, user)).isEqualTo(1);
            }
        } finally { executor.shutdownNow(); assertThat(executor.awaitTermination(20, java.util.concurrent.TimeUnit.SECONDS)).isTrue(); }
    }

    @Test void linkingRacesWithFirstSightingWithoutReassignmentOrOrphanUserAcross32Rounds() throws Exception {
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 32; round++) {
                var user = provisioning.resolveOrCreate(identity("established")).userId();
                var proof = issue(user); var target = identity("first-sighting");
                var beforeUsers = jdbc.queryForObject("SELECT count(*) FROM users.users", Integer.class);
                var barrier = new java.util.concurrent.CyclicBarrier(2);
                var link = executor.submit(() -> raceLink(proof, target, barrier));
                var sighting = executor.submit(() -> {
                    barrier.await(10, java.util.concurrent.TimeUnit.SECONDS);
                    return provisioning.resolveOrCreate(target).userId();
                });
                var linked = link.get(20, java.util.concurrent.TimeUnit.SECONDS);
                var resolved = sighting.get(20, java.util.concurrent.TimeUnit.SECONDS);
                assertThat(resolution.resolve(target).orElseThrow().userId()).isEqualTo(resolved);
                if (linked != null) { assertThat(linked).isEqualTo(user).isEqualTo(resolved); }
                else { assertThat(resolved).isNotEqualTo(user); }
                assertThat(jdbc.queryForObject("SELECT count(*) FROM users.users", Integer.class)).isEqualTo(beforeUsers + (linked == null ? 1 : 0));
                assertThat(jdbc.queryForObject("SELECT count(*) FROM users.external_identity_bindings WHERE issuer = ? AND subject = ?", Integer.class, target.issuer(), target.subject())).isEqualTo(1);
            }
        } finally { executor.shutdownNow(); assertThat(executor.awaitTermination(20, java.util.concurrent.TimeUnit.SECONDS)).isTrue(); }
    }

    @Test void providerMigrationRaceAlwaysRetainsAUsableIdentityAcross32Rounds() throws Exception {
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 32; round++) {
                var old = identity("migration-race"); var user = provisioning.resolveOrCreate(old).userId();
                var next = new ResolveExternalIdentityQuery("https://synthetic-next-provider.test", UUID.randomUUID().toString());
                var proof = issue(user); var oldId = bindingId(old); var barrier = new java.util.concurrent.CyclicBarrier(2);
                var link = executor.submit(() -> raceLink(proof, next, barrier));
                var unlink = executor.submit(() -> raceUnlink(user, oldId, barrier));
                assertThat(link.get(20, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(user);
                var removed = unlink.get(20, java.util.concurrent.TimeUnit.SECONDS);
                assertThat(resolution.resolve(next).orElseThrow().userId()).isEqualTo(user);
                assertThat(jdbc.queryForObject("SELECT count(*) FROM users.external_identity_bindings WHERE user_id = ? AND active", Integer.class, user)).isEqualTo(removed ? 1 : 2);
                assertThat(jdbc.queryForObject("SELECT count(*) FROM users.external_identity_bindings WHERE user_id = ?", Integer.class, user)).isEqualTo(2);
            }
        } finally { executor.shutdownNow(); assertThat(executor.awaitTermination(20, java.util.concurrent.TimeUnit.SECONDS)).isTrue(); }
    }

    @Test void issuanceAndCancellationAreAtomicWithTheirRequiredEvidence() {
        var user = provisioning.resolveOrCreate(identity("proof-audit")).userId();
        jdbc.execute("ALTER TABLE users.external_identity_events ADD CONSTRAINT synthetic_identity_issue_failure CHECK (action <> 'ISSUED') NOT VALID");
        try {
            assertThatThrownBy(() -> issue(user)).isInstanceOf(ExternalIdentityBindingPersistenceException.class);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM users.external_identity_link_proofs WHERE user_id = ?", Integer.class, user)).isZero();
        } finally { jdbc.execute("ALTER TABLE users.external_identity_events DROP CONSTRAINT synthetic_identity_issue_failure"); }
        var issued = (ExternalIdentityLinkIssuance.Issued) api.issue(user, UUID.randomUUID(), UUID.randomUUID());
        jdbc.execute("ALTER TABLE users.external_identity_events ADD CONSTRAINT synthetic_identity_cancel_failure CHECK (action <> 'CANCELLED') NOT VALID");
        try {
            assertThatThrownBy(() -> api.cancel(user, issued.proofId(), UUID.randomUUID())).isInstanceOf(ExternalIdentityBindingPersistenceException.class);
            assertThat(jdbc.queryForObject("SELECT consumed_at IS NULL AND cancelled_at IS NULL FROM users.external_identity_link_proofs WHERE proof_id = ?", Boolean.class, issued.proofId())).isTrue();
        } finally { jdbc.execute("ALTER TABLE users.external_identity_events DROP CONSTRAINT synthetic_identity_cancel_failure"); }
        assertThat(api.cancel(user, issued.proofId(), UUID.randomUUID())).isTrue();
    }

    @Test void proofExpiringDuringDatabaseLockWaitCannotLink() throws Exception {
        var user = provisioning.resolveOrCreate(identity("expiry-owner")).userId();
        var issued = (ExternalIdentityLinkIssuance.Issued) api.issue(user, UUID.randomUUID(), UUID.randomUUID()); var target = identity("expiry-target");
        jdbc.update("UPDATE users.external_identity_link_proofs SET expires_at = clock_timestamp() + interval '3 seconds' WHERE proof_id = ?", issued.proofId());
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        var locked = new java.util.concurrent.CountDownLatch(1); var release = new java.util.concurrent.CountDownLatch(1);
        var pids = new java.util.concurrent.ArrayBlockingQueue<Integer>(1);
        try {
            var owner = executor.submit(() -> new org.springframework.transaction.support.TransactionTemplate(manager).execute(status -> {
                jdbc.queryForObject("SELECT proof_id FROM users.external_identity_link_proofs WHERE proof_id = ? FOR UPDATE", UUID.class, issued.proofId()); locked.countDown();
                try { assertThat(release.await(12, java.util.concurrent.TimeUnit.SECONDS)).isTrue(); }
                catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw new AssertionError(exception); }
                return true;
            }));
            assertThat(locked.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            var consumer = executor.submit(() -> new org.springframework.transaction.support.TransactionTemplate(manager).execute(status -> {
                pids.add(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                return api.consume(issued.credential(), target.issuer(), target.subject());
            }));
            var pid = pids.poll(5, java.util.concurrent.TimeUnit.SECONDS); assertThat(pid).isNotNull();
            org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(2)).untilAsserted(() ->
                    assertThat(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM pg_locks WHERE pid = ? AND NOT granted)", Boolean.class, pid)).isTrue());
            assertThat(jdbc.queryForObject("SELECT clock_timestamp() < expires_at FROM users.external_identity_link_proofs WHERE proof_id = ?", Boolean.class, issued.proofId())).isTrue();
            org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(5)).until(() ->
                    jdbc.queryForObject("SELECT clock_timestamp() >= expires_at FROM users.external_identity_link_proofs WHERE proof_id = ?", Boolean.class, issued.proofId()));
            release.countDown(); assertThat(owner.get(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> consumer.get(5, java.util.concurrent.TimeUnit.SECONDS)).hasRootCauseInstanceOf(ExternalIdentityLifecycleUnavailableException.class);
            assertThat(resolution.resolve(target)).isEmpty();
            assertThat(jdbc.queryForObject("SELECT consumed_at IS NULL FROM users.external_identity_link_proofs WHERE proof_id = ?", Boolean.class, issued.proofId())).isTrue();
        } finally { release.countDown(); executor.shutdownNow(); assertThat(executor.awaitTermination(15, java.util.concurrent.TimeUnit.SECONDS)).isTrue(); }
    }
    private ResolveExternalIdentityQuery identity(String label) {
        return new ResolveExternalIdentityQuery("https://synthetic-identity-lifecycle.test", label + "-" + UUID.randomUUID());
    }
}
