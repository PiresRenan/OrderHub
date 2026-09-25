package io.github.piresrenan.orderhub.bootstrap;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import io.github.piresrenan.orderhub.authorization.adapter.out.persistence.postgresql.PostgreSqlAdministrativeGrantRepository;
import io.github.piresrenan.orderhub.authorization.adapter.out.persistence.postgresql.PostgreSqlFirstOperatorAuthorityRepository;
import io.github.piresrenan.orderhub.authorization.application.port.in.bootstrap.FirstOperatorPlatformAuthorityUseCase;
import io.github.piresrenan.orderhub.authorization.application.service.FirstOperatorPlatformAuthorityService;
import io.github.piresrenan.orderhub.bootstrap.adapter.out.persistence.postgresql.PostgreSqlFirstOperatorCeremonyRepository;
import io.github.piresrenan.orderhub.bootstrap.adapter.out.transaction.spring.SpringFirstOperatorBootstrapTransaction;
import io.github.piresrenan.orderhub.bootstrap.application.port.in.FirstOperatorBootstrapOutcome;
import io.github.piresrenan.orderhub.bootstrap.application.port.in.FirstOperatorBootstrapRequest;
import io.github.piresrenan.orderhub.bootstrap.application.port.out.FirstOperatorCeremonyRepository;
import io.github.piresrenan.orderhub.bootstrap.application.port.out.FirstOperatorCeremonyState;
import io.github.piresrenan.orderhub.bootstrap.application.service.FirstOperatorBootstrapService;
import io.github.piresrenan.orderhub.users.adapter.out.persistence.postgresql.PostgreSqlExternalIdentityBindingRepository;
import io.github.piresrenan.orderhub.users.adapter.out.persistence.postgresql.PostgreSqlExternalIdentityLifecycleRepository;
import io.github.piresrenan.orderhub.users.adapter.out.persistence.postgresql.PostgreSqlExternalIdentitySerializationCoordinator;
import io.github.piresrenan.orderhub.users.adapter.out.persistence.postgresql.PostgreSqlUserRepository;
import io.github.piresrenan.orderhub.users.application.port.in.BindExternalIdentityUseCase;
import io.github.piresrenan.orderhub.users.application.port.in.EstablishNewExternalUserUseCase;
import io.github.piresrenan.orderhub.users.application.port.in.ResolveExternalIdentityQuery;
import io.github.piresrenan.orderhub.users.application.service.BindExternalIdentityService;
import io.github.piresrenan.orderhub.users.application.service.CreateUserService;
import io.github.piresrenan.orderhub.users.application.service.ResolveExternalIdentityService;
import io.github.piresrenan.orderhub.users.application.service.ResolveOrCreateExternalUserService;

/**
 * Why: the ceremony's safety is decided by PostgreSQL, so its evidence must come from a real database.
 * Scenario: each test gets a fresh database cloned from a V1-V46 template; the production Users,
 * Authorization and bootstrap graph runs over one JdbcTransactionManager, exactly as composed at runtime.
 * Covers: positive transition, untrusted issuer, replay semantics, fail-closed existing state, concurrency
 * with different and identical identities, failure atomicity F1-F5 and the schema's forward-only guards.
 * Prevents: partial privileged state, a second winner, broadened authority and process-local correctness.
 */
@Testcontainers
class FirstOperatorBootstrapPostgreSqlTest {

    private static final String TRUSTED = "https://identity.bootstrap.test";
    private static final String SUBJECT = "4f7b1c2e-first-operator";
    private static final String TEMPLATE = "bootstrap_template";

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("postgres:18.6-trixie@sha256:"
                    + "4ef4dbc939d61acea57712655ddb4b4ab27419c913f94cca0cd57cb3ea3c2280")
                    .asCompatibleSubstituteFor("postgres"));

    private static final AtomicInteger DATABASES = new AtomicInteger();

    private DataSource source;
    private JdbcTemplate jdbc;
    private JdbcTransactionManager manager;

    @BeforeAll
    static void migrateTemplate() {
        admin().execute("CREATE DATABASE " + TEMPLATE);
        var template = dataSource(TEMPLATE);
        Flyway.configure().dataSource(template).locations("classpath:db/migration").load().migrate();
    }

    @BeforeEach
    void freshDatabase() {
        var name = "bootstrap_case_" + DATABASES.incrementAndGet();
        admin().execute("CREATE DATABASE " + name + " TEMPLATE " + TEMPLATE);
        source = dataSource(name);
        jdbc = new JdbcTemplate(source);
        manager = new JdbcTransactionManager(source);
    }

    @Test
    void firstTrustedBootstrapEstablishesExactlyOneBoundMinimalPlatformOperator() {
        assertThat(resolver().resolve(identity())).isEmpty();

        var outcome = graph().bootstrap(request(UUID.randomUUID()));

        assertThat(outcome).isEqualTo(FirstOperatorBootstrapOutcome.COMPLETED);
        var userId = resolver().resolve(identity()).orElseThrow().userId();
        assertThat(count("users.users")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users.external_identity_bindings WHERE issuer = ? AND subject = ? AND user_id = ?",
                Long.class, TRUSTED, SUBJECT, userId)).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT user_id, scope_type, scope_id, permission_code FROM access_control.administrative_grants"))
                .containsExactly(row("user_id", userId, "scope_type", "PLATFORM", "scope_id", null, "permission_code", "PLATFORM_TENANTS_MANAGE"));
        assertThat(count("access_control.administrative_grant_audit_events")).isZero();
        assertThat(count("access_control.role_assignments")).isZero();
        assertThat(count("access_control.user_permission_overrides")).isZero();
        assertThat(count("users.tenant_memberships")).isZero();
        assertThat(count("workforce.staff_profiles")).isZero();
        assertThat(count("customers.customer_profiles")).isZero();
        assertThat(jdbc.queryForObject("SELECT state FROM bootstrap.first_operator_ceremony", String.class)).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("SELECT operator_user_id FROM bootstrap.first_operator_ceremony", UUID.class)).isEqualTo(userId);
        assertThat(jdbc.queryForList("SELECT outcome, operator_user_id, granted_permission FROM bootstrap.first_operator_ceremony_events"))
                .containsExactly(row("outcome", "COMPLETED", "operator_user_id", userId, "granted_permission", "PLATFORM_TENANTS_MANAGE"));
        assertThat(bootstrapText()).doesNotContain(SUBJECT, TRUSTED);
        assertThat(jdbc.queryForObject("SELECT request_fingerprint FROM bootstrap.first_operator_ceremony", String.class))
                .matches("[0-9a-f]{64}");
    }

    @Test
    void r1ReplayOfTheOriginalRequestSurvivesUnlinkingTheOriginalBinding() {
        var operation = UUID.randomUUID();
        assertThat(graph().bootstrap(request(operation))).isEqualTo(FirstOperatorBootstrapOutcome.COMPLETED);
        var operator = operatorUserId();
        var lifecycle = new PostgreSqlExternalIdentityLifecycleRepository(jdbc);
        inTransaction(() -> {
            var binding = lifecycle.accounts(operator).get(0).id();
            assertThat(lifecycle.unlink(operator, binding)).isTrue();
        });
        assertThat(resolver().resolve(identity())).isEmpty();
        var before = snapshot();

        assertThat(graph().bootstrap(request(operation))).isEqualTo(FirstOperatorBootstrapOutcome.ALREADY_COMPLETED_SAME_OPERATION);

        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    void r2AnotherIdentityLaterLinkedToTheOperatorIsNotTheSameRequest() {
        var operation = UUID.randomUUID();
        assertThat(graph().bootstrap(request(operation))).isEqualTo(FirstOperatorBootstrapOutcome.COMPLETED);
        var operator = operatorUserId();
        inTransaction(() -> new PostgreSqlExternalIdentityLifecycleRepository(jdbc).link(operator, TRUSTED, "linked-later"));
        var alternate = new ResolveExternalIdentityQuery(TRUSTED, "linked-later");
        assertThat(resolver().resolve(alternate).orElseThrow().userId()).isEqualTo(operator);
        var before = snapshot();

        assertThat(graph().bootstrap(new FirstOperatorBootstrapRequest(TRUSTED, "linked-later", operation)))
                .isEqualTo(FirstOperatorBootstrapOutcome.ALREADY_COMPLETED);
        assertThat(graph().bootstrap(request(UUID.randomUUID()))).isEqualTo(FirstOperatorBootstrapOutcome.ALREADY_COMPLETED);

        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    void r3ReplayOfTheOriginalRequestSurvivesRetiringTheIssuerFromTrust() {
        var operation = UUID.randomUUID();
        assertThat(graph().bootstrap(request(operation))).isEqualTo(FirstOperatorBootstrapOutcome.COMPLETED);
        var before = snapshot();
        var retired = new FirstOperatorBootstrapService(issuer -> false, new SpringFirstOperatorBootstrapTransaction(manager), ceremony(),
                resolver(), usersGraph(), authority(), UUID::randomUUID);

        assertThat(retired.bootstrap(request(operation))).isEqualTo(FirstOperatorBootstrapOutcome.ALREADY_COMPLETED_SAME_OPERATION);
        assertThat(retired.bootstrap(request(UUID.randomUUID()))).isEqualTo(FirstOperatorBootstrapOutcome.ALREADY_COMPLETED);

        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    void r5CompletedStateRequiresAWellFormedFingerprint() {
        for (var malformed : new String[] {null, "", "A".repeat(64), "0".repeat(63), "0".repeat(65), "g".repeat(64), " " + "0".repeat(63)}) {
            assertThatThrownBy(() -> jdbc.update("""
                    UPDATE bootstrap.first_operator_ceremony
                    SET state = 'COMPLETED', operation_id = ?, operator_user_id = ?, request_fingerprint = ?, completed_at = now()
                    """, UUID.randomUUID(), UUID.randomUUID(), malformed)).as(String.valueOf(malformed)).isInstanceOf(DataAccessException.class);
        }
        assertThatThrownBy(() -> jdbc.update("UPDATE bootstrap.first_operator_ceremony SET request_fingerprint = ?", "0".repeat(64)))
                .isInstanceOf(DataAccessException.class);
        assertThat(state()).isEqualTo("OPEN");
        assertThat(jdbc.queryForObject("SELECT request_fingerprint FROM bootstrap.first_operator_ceremony", String.class)).isNull();
    }

    @Test
    void r4UntrustedIssuerIsRejectedBeforeAnyMutation() {
        var outcome = graph().bootstrap(new FirstOperatorBootstrapRequest("https://attacker.example.test", SUBJECT, UUID.randomUUID()));

        assertThat(outcome).isEqualTo(FirstOperatorBootstrapOutcome.UNTRUSTED_ISSUER);
        assertNothingEstablished();
    }

    @Test
    void secondBootstrapNeverMutatesAgainAndReplayIsDeterministic() {
        var operation = UUID.randomUUID();
        assertThat(graph().bootstrap(request(operation))).isEqualTo(FirstOperatorBootstrapOutcome.COMPLETED);
        var before = snapshot();

        // A rerun after lost output: same operation and exact identity.
        assertThat(graph().bootstrap(request(operation))).isEqualTo(FirstOperatorBootstrapOutcome.ALREADY_COMPLETED_SAME_OPERATION);
        // A different ordinary bootstrap, a different identity, and the same operation with another identity.
        assertThat(graph().bootstrap(request(UUID.randomUUID()))).isEqualTo(FirstOperatorBootstrapOutcome.ALREADY_COMPLETED);
        assertThat(graph().bootstrap(new FirstOperatorBootstrapRequest(TRUSTED, "another-subject", UUID.randomUUID())))
                .isEqualTo(FirstOperatorBootstrapOutcome.ALREADY_COMPLETED);
        assertThat(graph().bootstrap(new FirstOperatorBootstrapRequest(TRUSTED, "another-subject", operation)))
                .isEqualTo(FirstOperatorBootstrapOutcome.ALREADY_COMPLETED);

        assertThat(snapshot()).isEqualTo(before);
        assertThat(resolver().resolve(new ResolveExternalIdentityQuery(TRUSTED, "another-subject"))).isEmpty();
    }

    @Test
    void newProcessGraphObservesDurableClosedState() {
        assertThat(graph().bootstrap(request(UUID.randomUUID()))).isEqualTo(FirstOperatorBootstrapOutcome.COMPLETED);
        var before = snapshot();

        // A new data source, transaction manager and graph share nothing in memory with the first one.
        var restarted = new FirstOperatorBootstrapPostgreSqlTest();
        restarted.source = new DriverManagerDataSource(url(currentDatabase()), POSTGRES.getUsername(), POSTGRES.getPassword());
        restarted.jdbc = new JdbcTemplate(restarted.source);
        restarted.manager = new JdbcTransactionManager(restarted.source);

        assertThat(restarted.graph().bootstrap(request(UUID.randomUUID()))).isEqualTo(FirstOperatorBootstrapOutcome.ALREADY_COMPLETED);
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    void existingPlatformAuthorityFailsClosed() {
        var existing = UUID.randomUUID();
        jdbc.update("INSERT INTO users.users (id) VALUES (?)", existing);
        jdbc.update("""
                INSERT INTO access_control.administrative_grants (grant_id, user_id, scope_type, scope_id, permission_code)
                VALUES (?, ?, 'PLATFORM', NULL, 'PLATFORM_ORGANIZATIONS_VIEW')
                """, UUID.randomUUID(), existing);
        var before = snapshot();

        assertThat(graph().bootstrap(request(UUID.randomUUID()))).isEqualTo(FirstOperatorBootstrapOutcome.INCOMPATIBLE_EXISTING_STATE);

        assertThat(snapshot()).isEqualTo(before);
        assertThat(state()).isEqualTo("OPEN");
    }

    @Test
    void alreadyBoundIdentityIsNeverElevated() {
        var existing = UUID.randomUUID();
        jdbc.update("INSERT INTO users.users (id) VALUES (?)", existing);
        jdbc.update("INSERT INTO users.external_identity_bindings (issuer, subject, user_id) VALUES (?, ?, ?)", TRUSTED, SUBJECT, existing);
        var before = snapshot();

        assertThat(graph().bootstrap(request(UUID.randomUUID()))).isEqualTo(FirstOperatorBootstrapOutcome.INCOMPATIBLE_EXISTING_STATE);

        assertThat(snapshot()).isEqualTo(before);
        assertThat(count("access_control.administrative_grants")).isZero();
        assertThat(state()).isEqualTo("OPEN");
    }

    @Test
    void identityBoundAfterThePreCheckIsRefusedByExclusiveEstablishment() {
        var existing = UUID.randomUUID();
        jdbc.update("INSERT INTO users.users (id) VALUES (?)", existing);
        jdbc.update("INSERT INTO users.external_identity_bindings (issuer, subject, user_id) VALUES (?, ?, ?)", TRUSTED, SUBJECT, existing);
        var before = snapshot();
        // The pre-check misses the binding, as if it committed between the check and the serialized scope.
        var racing = new FirstOperatorBootstrapService(TRUSTED::equals, new SpringFirstOperatorBootstrapTransaction(manager), ceremony(),
                query -> java.util.Optional.empty(), usersGraph(), authority(), UUID::randomUUID);

        assertThat(racing.bootstrap(request(UUID.randomUUID()))).isEqualTo(FirstOperatorBootstrapOutcome.INCOMPATIBLE_EXISTING_STATE);

        assertThat(snapshot()).isEqualTo(before);
        assertThatThrownBy(() -> usersGraph().establishNew(identity()))
                .isInstanceOf(io.github.piresrenan.orderhub.users.application.port.in.ExternalIdentityAlreadyBoundException.class);
    }

    @Test
    void unrelatedExistingUsersAreNeitherAdoptedNorElevated() {
        var unrelated = UUID.randomUUID();
        jdbc.update("INSERT INTO users.users (id) VALUES (?)", unrelated);
        jdbc.update("INSERT INTO users.external_identity_bindings (issuer, subject, user_id) VALUES (?, 'unrelated', ?)", TRUSTED, unrelated);

        assertThat(graph().bootstrap(request(UUID.randomUUID()))).isEqualTo(FirstOperatorBootstrapOutcome.COMPLETED);

        var operator = resolver().resolve(identity()).orElseThrow().userId();
        assertThat(operator).isNotEqualTo(unrelated);
        assertThat(count("users.users")).isEqualTo(2);
        assertThat(jdbc.queryForList("SELECT user_id FROM access_control.administrative_grants", UUID.class)).containsExactly(operator);
    }

    @Test
    void concurrentBootstrapsOfDifferentIdentitiesHaveExactlyOneWinner() throws Exception {
        var outcomes = race(request(UUID.randomUUID()), new FirstOperatorBootstrapRequest(TRUSTED, "competing-subject", UUID.randomUUID()));

        assertThat(outcomes).containsExactlyInAnyOrder(FirstOperatorBootstrapOutcome.COMPLETED, FirstOperatorBootstrapOutcome.ALREADY_COMPLETED);
        assertSingleOperator();
        assertThat(resolver().resolve(new ResolveExternalIdentityQuery(TRUSTED, "competing-subject"))).isEmpty();
    }

    @Test
    void concurrentBootstrapsOfTheSameIdentityPreserveExactIdentitySerialization() throws Exception {
        var outcomes = race(request(UUID.randomUUID()), request(UUID.randomUUID()));
        assertThat(outcomes).containsExactlyInAnyOrder(FirstOperatorBootstrapOutcome.COMPLETED, FirstOperatorBootstrapOutcome.ALREADY_COMPLETED);
        assertSingleOperator();

        freshDatabase();
        var operation = UUID.randomUUID();
        var replayed = race(request(operation), request(operation));
        assertThat(replayed).containsExactlyInAnyOrder(FirstOperatorBootstrapOutcome.COMPLETED,
                FirstOperatorBootstrapOutcome.ALREADY_COMPLETED_SAME_OPERATION);
        assertSingleOperator();
    }

    @Test
    void f1FailingBindingAfterUserCreationRollsEverythingBack() {
        BindExternalIdentityUseCase failingBinder = command -> {
            throw new InjectedFault();
        };
        var users = new ResolveOrCreateExternalUserService(coordinator(), resolver(), new CreateUserService(new PostgreSqlUserRepository(jdbc), UUID::randomUUID), failingBinder);

        assertThatThrownBy(() -> graph(users, authority(), ceremony()).bootstrap(request(UUID.randomUUID()))).isInstanceOf(InjectedFault.class);

        assertNothingEstablished();
    }

    @Test
    void f2FailingAfterUserAndBindingBeforeGrantRollsEverythingBack() {
        EstablishNewExternalUserUseCase users = query -> {
            usersGraph().establishNew(query);
            throw new InjectedFault();
        };

        assertThatThrownBy(() -> graph(users, authority(), ceremony()).bootstrap(request(UUID.randomUUID()))).isInstanceOf(InjectedFault.class);

        assertNothingEstablished();
    }

    @Test
    void f3FailingGrantPersistenceRollsEverythingBack() {
        var real = authority();
        var failing = new FirstOperatorPlatformAuthorityUseCase() {
            @Override public boolean platformAuthorityExists() { return real.platformAuthorityExists(); }
            @Override public void establishFirstOperatorAuthority(UUID userId) {
                real.establishFirstOperatorAuthority(userId);
                throw new InjectedFault();
            }
        };

        assertThatThrownBy(() -> graph(usersGraph(), failing, ceremony()).bootstrap(request(UUID.randomUUID()))).isInstanceOf(InjectedFault.class);

        assertNothingEstablished();
    }

    @Test
    void f4FailingEvidencePersistenceRollsEverythingBack() {
        var real = ceremony();
        var failing = new DelegatingCeremony(real) {
            @Override public void appendCompletedEvidence(UUID eventId, UUID operationId, UUID operatorUserId) {
                real.appendCompletedEvidence(eventId, operationId, operatorUserId);
                throw new InjectedFault();
            }
        };

        assertThatThrownBy(() -> graph(usersGraph(), authority(), failing).bootstrap(request(UUID.randomUUID()))).isInstanceOf(InjectedFault.class);

        assertNothingEstablished();
    }

    @Test
    void f5FailingFinalStateTransitionRollsEverythingBack() {
        var real = ceremony();
        var failing = new DelegatingCeremony(real) {
            @Override public void complete(UUID operationId, UUID operatorUserId, String requestFingerprint) {
                real.complete(operationId, operatorUserId, requestFingerprint);
                throw new InjectedFault();
            }
        };

        assertThatThrownBy(() -> graph(usersGraph(), authority(), failing).bootstrap(request(UUID.randomUUID()))).isInstanceOf(InjectedFault.class);

        assertNothingEstablished();
        // The rolled-back attempt left the ceremony usable for a correct retry.
        assertThat(graph().bootstrap(request(UUID.randomUUID()))).isEqualTo(FirstOperatorBootstrapOutcome.COMPLETED);
    }

    @Test
    void everyOwnerStepRunsInTheSamePhysicalPostgreSqlTransaction() {
        var observed = new java.util.LinkedHashMap<String, String>();
        java.util.function.Consumer<String> record = step -> {
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).as(step).isTrue();
            observed.put(step, jdbc.queryForObject("SELECT pg_current_xact_id()::text || '/' || pg_backend_pid()", String.class));
        };
        var bindings = new PostgreSqlExternalIdentityBindingRepository(jdbc);
        var creator = new CreateUserService(new PostgreSqlUserRepository(jdbc), UUID::randomUUID);
        var binder = new BindExternalIdentityService(bindings);
        var users = new ResolveOrCreateExternalUserService(coordinator(), resolver(),
                () -> { var created = creator.create(); record.accept("users.create"); return created; },
                command -> { binder.bind(command); record.accept("users.bind"); });
        var realAuthority = authority();
        var authority = new FirstOperatorPlatformAuthorityUseCase() {
            @Override public boolean platformAuthorityExists() { return realAuthority.platformAuthorityExists(); }
            @Override public void establishFirstOperatorAuthority(UUID userId) {
                realAuthority.establishFirstOperatorAuthority(userId);
                record.accept("authorization.grant");
            }
        };
        var real = ceremony();
        var ceremony = new DelegatingCeremony(real) {
            @Override public FirstOperatorCeremonyState lock() { var state = real.lock(); record.accept("bootstrap.lock"); return state; }
            @Override public void appendCompletedEvidence(UUID eventId, UUID operationId, UUID operatorUserId) {
                real.appendCompletedEvidence(eventId, operationId, operatorUserId);
                record.accept("bootstrap.evidence");
            }
            @Override public void complete(UUID operationId, UUID operatorUserId, String requestFingerprint) {
                real.complete(operationId, operatorUserId, requestFingerprint);
                record.accept("bootstrap.complete");
            }
        };

        assertThat(graph(users, authority, ceremony).bootstrap(request(UUID.randomUUID()))).isEqualTo(FirstOperatorBootstrapOutcome.COMPLETED);

        assertThat(observed).containsOnlyKeys("bootstrap.lock", "users.create", "users.bind", "authorization.grant",
                "bootstrap.evidence", "bootstrap.complete");
        assertThat(new java.util.HashSet<>(observed.values())).as(observed.toString()).hasSize(1);
    }

    @Test
    void evidenceInconsistentWithOpenStateFailsClosedWithoutMutation() {
        // E: success evidence exists although the singleton is OPEN; the unique success constraint rejects a second one.
        jdbc.update("""
                INSERT INTO bootstrap.first_operator_ceremony_events (event_id, ceremony, operation_id, outcome, operator_user_id, granted_permission)
                VALUES (?, 'RETAINED_FIRST_OPERATOR_BOOTSTRAP', ?, 'COMPLETED', ?, 'PLATFORM_TENANTS_MANAGE')
                """, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        var before = snapshot();

        assertThatThrownBy(() -> graph().bootstrap(request(UUID.randomUUID()))).isInstanceOf(DataAccessException.class);

        assertThat(snapshot()).isEqualTo(before);
        assertThat(state()).isEqualTo("OPEN");
    }

    @Test
    void missingSingletonFailsClosedWithoutMutation() {
        // F: only reachable by bypassing the forward-only trigger, which the ceremony never does.
        jdbc.execute("ALTER TABLE bootstrap.first_operator_ceremony DISABLE TRIGGER trg_bootstrap_first_operator_ceremony_forward_only");
        jdbc.update("DELETE FROM bootstrap.first_operator_ceremony");

        assertThatThrownBy(() -> graph().bootstrap(request(UUID.randomUUID()))).isInstanceOf(DataAccessException.class);

        assertThat(count("users.users")).isZero();
        assertThat(count("users.external_identity_bindings")).isZero();
        assertThat(count("access_control.administrative_grants")).isZero();
        assertThat(count("bootstrap.first_operator_ceremony_events")).isZero();
    }

    @Test
    void completedStateIsNeverReopenedByTheRepositoryApi() {
        assertThat(graph().bootstrap(request(UUID.randomUUID()))).isEqualTo(FirstOperatorBootstrapOutcome.COMPLETED);
        var before = snapshot();

        assertThatThrownBy(() -> new SpringFirstOperatorBootstrapTransaction(manager).execute(() -> {
            ceremony().complete(UUID.randomUUID(), UUID.randomUUID(), "0".repeat(64));
            return null;
        })).isInstanceOf(IllegalStateException.class);

        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    void normalBootstrapCannotBeUsedForSuccessionOrReopenedAfterAccessLoss() {
        // OH-026 R8B/R9/R10: a retired issuer or a replacement identity is never a bootstrap input.
        // This proves the normal ceremony cannot be used for succession; it does not make succession supported.
        var operation = UUID.randomUUID();
        assertThat(graph().bootstrap(request(operation))).isEqualTo(FirstOperatorBootstrapOutcome.COMPLETED);
        var before = snapshot();
        var retired = new FirstOperatorBootstrapService(issuer -> false, new SpringFirstOperatorBootstrapTransaction(manager), ceremony(),
                resolver(), usersGraph(), authority(), UUID::randomUUID);

        assertThat(retired.bootstrap(new FirstOperatorBootstrapRequest(TRUSTED, "replacement-subject", UUID.randomUUID())))
                .isEqualTo(FirstOperatorBootstrapOutcome.ALREADY_COMPLETED);
        assertThat(retired.bootstrap(new FirstOperatorBootstrapRequest("https://replacement.bootstrap.test", "replacement-subject",
                UUID.randomUUID()))).isEqualTo(FirstOperatorBootstrapOutcome.ALREADY_COMPLETED);
        assertThat(retired.bootstrap(request(operation))).isEqualTo(FirstOperatorBootstrapOutcome.ALREADY_COMPLETED_SAME_OPERATION);

        assertThat(snapshot()).isEqualTo(before);
        assertThat(state()).isEqualTo("COMPLETED");
    }

    @Test
    void ownerWritesRefuseToRunOutsideTheCallerTransaction() {
        assertThatThrownBy(() -> ceremony().lock()).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> ceremony().appendCompletedEvidence(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> ceremony().complete(UUID.randomUUID(), UUID.randomUUID(), "0".repeat(64))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> authority().platformAuthorityExists()).isInstanceOf(IllegalStateException.class);
        assertNothingEstablished();
    }

    @Test
    void schemaAllowsOnlyTheForwardTransitionAndAppendOnlySingleEvidence() {
        assertThatThrownBy(() -> jdbc.update("INSERT INTO bootstrap.first_operator_ceremony (ceremony, state) VALUES ('RETAINED_FIRST_OPERATOR_BOOTSTRAP', 'OPEN')"))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM bootstrap.first_operator_ceremony")).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.execute("TRUNCATE bootstrap.first_operator_ceremony CASCADE")).isInstanceOf(DataAccessException.class);
        assertThat(graph().bootstrap(request(UUID.randomUUID()))).isEqualTo(FirstOperatorBootstrapOutcome.COMPLETED);

        assertThatThrownBy(() -> jdbc.update("UPDATE bootstrap.first_operator_ceremony SET state = 'OPEN', operation_id = NULL, operator_user_id = NULL, completed_at = NULL"))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE bootstrap.first_operator_ceremony SET operator_user_id = ?", UUID.randomUUID()))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE bootstrap.first_operator_ceremony_events SET operator_user_id = ?", UUID.randomUUID()))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM bootstrap.first_operator_ceremony_events")).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.execute("TRUNCATE bootstrap.first_operator_ceremony_events")).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO bootstrap.first_operator_ceremony_events (event_id, ceremony, operation_id, outcome, operator_user_id, granted_permission)
                VALUES (?, 'RETAINED_FIRST_OPERATOR_BOOTSTRAP', ?, 'COMPLETED', ?, 'PLATFORM_TENANTS_MANAGE')
                """, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())).isInstanceOf(DataAccessException.class);
        assertThat(state()).isEqualTo("COMPLETED");
        assertThat(count("bootstrap.first_operator_ceremony_events")).isEqualTo(1);
    }

    /** Holds the first attempt inside its locked transaction until the second is observed blocked in PostgreSQL. */
    private List<FirstOperatorBootstrapOutcome> race(FirstOperatorBootstrapRequest first, FirstOperatorBootstrapRequest second) throws Exception {
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var holding = new AtomicBoolean(true);
        var real = ceremony();
        var gated = new DelegatingCeremony(real) {
            @Override public FirstOperatorCeremonyState lock() {
                var state = real.lock();
                if (holding.compareAndSet(true, false)) {
                    locked.countDown();
                    await(release);
                }
                return state;
            }
        };
        var executor = Executors.newFixedThreadPool(2);
        try {
            Future<FirstOperatorBootstrapOutcome> winner = executor.submit(() -> graph(usersGraph(), authority(), gated).bootstrap(first));
            assertThat(locked.await(15, SECONDS)).isTrue();
            Future<FirstOperatorBootstrapOutcome> loser = executor.submit(() -> graph(usersGraph(), authority(), gated).bootstrap(second));
            awaitBlockedOnRowLock();
            release.countDown();
            var outcomes = new ArrayList<FirstOperatorBootstrapOutcome>();
            outcomes.add(winner.get(30, SECONDS));
            outcomes.add(loser.get(30, SECONDS));
            return outcomes;
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    /** Deterministic rendezvous: PostgreSQL itself reports the competing session waiting for the singleton lock. */
    private void awaitBlockedOnRowLock() throws InterruptedException {
        var deadline = System.nanoTime() + SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            var waiting = jdbc.queryForObject("""
                    SELECT count(*) FROM pg_stat_activity
                    WHERE datname = current_database() AND wait_event_type = 'Lock' AND query LIKE '%first_operator_ceremony%'
                    """, Long.class);
            if (waiting == 1) {
                return;
            }
            Thread.onSpinWait();
            Thread.sleep(5);
        }
        throw new AssertionError("Competing bootstrap never blocked on the ceremony row");
    }

    private void assertSingleOperator() {
        assertThat(count("users.users")).isEqualTo(1);
        assertThat(count("users.external_identity_bindings")).isEqualTo(1);
        assertThat(count("access_control.administrative_grants")).isEqualTo(1);
        assertThat(count("bootstrap.first_operator_ceremony")).isEqualTo(1);
        assertThat(state()).isEqualTo("COMPLETED");
        assertThat(count("bootstrap.first_operator_ceremony_events")).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM users.users u
                WHERE NOT EXISTS (SELECT 1 FROM users.external_identity_bindings b WHERE b.user_id = u.id)
                """, Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT user_id FROM access_control.administrative_grants", UUID.class))
                .isEqualTo(resolver().resolve(identity()).orElseThrow().userId());
    }

    private void assertNothingEstablished() {
        assertThat(count("users.users")).isZero();
        assertThat(count("users.external_identity_bindings")).isZero();
        assertThat(count("access_control.administrative_grants")).isZero();
        assertThat(count("access_control.administrative_grant_audit_events")).isZero();
        assertThat(count("bootstrap.first_operator_ceremony_events")).isZero();
        assertThat(state()).isEqualTo("OPEN");
    }

    private List<Object> snapshot() {
        return List.of(
                jdbc.queryForList("SELECT * FROM users.users ORDER BY id"),
                jdbc.queryForList("SELECT * FROM users.external_identity_bindings ORDER BY user_id"),
                jdbc.queryForList("SELECT * FROM access_control.administrative_grants ORDER BY grant_id"),
                jdbc.queryForList("SELECT * FROM access_control.administrative_grant_audit_events"),
                jdbc.queryForList("SELECT * FROM bootstrap.first_operator_ceremony"),
                jdbc.queryForList("SELECT * FROM bootstrap.first_operator_ceremony_events"));
    }

    private String bootstrapText() {
        return jdbc.queryForList("SELECT * FROM bootstrap.first_operator_ceremony").toString()
                + jdbc.queryForList("SELECT * FROM bootstrap.first_operator_ceremony_events");
    }

    private FirstOperatorBootstrapService graph() {
        return graph(usersGraph(), authority(), ceremony());
    }

    private FirstOperatorBootstrapService graph(EstablishNewExternalUserUseCase users, FirstOperatorPlatformAuthorityUseCase authority,
            FirstOperatorCeremonyRepository ceremony) {
        return new FirstOperatorBootstrapService(TRUSTED::equals, new SpringFirstOperatorBootstrapTransaction(manager), ceremony,
                resolver(), users, authority, UUID::randomUUID);
    }

    private ResolveOrCreateExternalUserService usersGraph() {
        var bindings = new PostgreSqlExternalIdentityBindingRepository(jdbc);
        return new ResolveOrCreateExternalUserService(coordinator(), resolver(),
                new CreateUserService(new PostgreSqlUserRepository(jdbc), UUID::randomUUID), new BindExternalIdentityService(bindings));
    }

    private PostgreSqlExternalIdentitySerializationCoordinator coordinator() {
        return new PostgreSqlExternalIdentitySerializationCoordinator(jdbc, manager);
    }

    private ResolveExternalIdentityService resolver() {
        return new ResolveExternalIdentityService(new PostgreSqlExternalIdentityBindingRepository(jdbc));
    }

    private FirstOperatorPlatformAuthorityService authority() {
        return new FirstOperatorPlatformAuthorityService(new PostgreSqlFirstOperatorAuthorityRepository(jdbc),
                new PostgreSqlAdministrativeGrantRepository(jdbc));
    }

    private PostgreSqlFirstOperatorCeremonyRepository ceremony() {
        return new PostgreSqlFirstOperatorCeremonyRepository(jdbc);
    }

    private static FirstOperatorBootstrapRequest request(UUID operation) {
        return new FirstOperatorBootstrapRequest(TRUSTED, SUBJECT, operation);
    }

    private static ResolveExternalIdentityQuery identity() {
        return new ResolveExternalIdentityQuery(TRUSTED, SUBJECT);
    }

    private UUID operatorUserId() {
        return jdbc.queryForObject("SELECT operator_user_id FROM bootstrap.first_operator_ceremony", UUID.class);
    }

    private void inTransaction(Runnable work) {
        new org.springframework.transaction.support.TransactionTemplate(manager).executeWithoutResult(status -> work.run());
    }

    private String state() {
        return jdbc.queryForObject("SELECT state FROM bootstrap.first_operator_ceremony", String.class);
    }

    private long count(String relation) {
        return jdbc.queryForObject("SELECT count(*) FROM " + relation, Long.class);
    }

    private String currentDatabase() {
        return jdbc.queryForObject("SELECT current_database()", String.class);
    }

    private static Map<String, Object> row(Object... pairs) {
        var row = new java.util.LinkedHashMap<String, Object>();
        for (var index = 0; index < pairs.length; index += 2) {
            row.put((String) pairs[index], pairs[index + 1]);
        }
        return row;
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(30, SECONDS)) {
                throw new AssertionError("Race release timed out");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }

    private static JdbcTemplate admin() {
        return new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
    }

    private static DataSource dataSource(String name) {
        return new DriverManagerDataSource(url(name), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static String url(String name) {
        // Names are fixed literals or counters in this test; never an external identifier.
        if (!name.matches("[a-z_0-9]+")) { throw new IllegalArgumentException("Invalid test database name"); }
        return "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + "/" + name;
    }

    /** Test-only fault marker; never a production exception type. */
    private static final class InjectedFault extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    /** Delegates every ceremony operation; subclasses override exactly one boundary to inject a fault or gate. */
    private static class DelegatingCeremony implements FirstOperatorCeremonyRepository {
        private final FirstOperatorCeremonyRepository delegate;

        DelegatingCeremony(FirstOperatorCeremonyRepository delegate) { this.delegate = delegate; }

        @Override public FirstOperatorCeremonyState lock() { return delegate.lock(); }

        @Override public void appendCompletedEvidence(UUID eventId, UUID operationId, UUID operatorUserId) {
            delegate.appendCompletedEvidence(eventId, operationId, operatorUserId);
        }

        @Override public void complete(UUID operationId, UUID operatorUserId, String requestFingerprint) {
            delegate.complete(operationId, operatorUserId, requestFingerprint);
        }
    }
}
