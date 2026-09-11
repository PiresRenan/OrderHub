package io.github.piresrenan.orderhub.authorization.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.time.Duration;
import java.util.UUID;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import io.github.piresrenan.orderhub.authorization.adapter.out.persistence.postgresql.*;
import io.github.piresrenan.orderhub.authorization.application.port.in.provisioning.*;
import io.github.piresrenan.orderhub.authorization.application.port.out.StaffProvisioningAuthorizationRepository;
import io.github.piresrenan.orderhub.authorization.application.service.StaffProvisioningAuthorizationService;
import io.github.piresrenan.orderhub.authorization.domain.model.*;

/**
 * Why: current privilege must remain stable through a provisioning mutation.
 * Covers: database arbitration against existing writers and bounded immutable evidence.
 * Prevents: revocation bypass and an independently committed unaudited role assignment.
 */
@Testcontainers
class StaffProvisioningAuthorizationPersistenceTest {
    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("postgres:18.6-trixie@sha256:4ef4dbc939d61acea57712655ddb4b4ab27419c913f94cca0cd57cb3ea3c2280")
                    .asCompatibleSubstituteFor("postgres"));
    private static JdbcTemplate jdbc;
    private static TransactionTemplate transaction;

    @BeforeAll
    static void migrate() {
        var source = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(source).load().migrate();
        jdbc = new JdbcTemplate(source);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
        transaction.setTimeout(12);
    }

    @Test
    void roleAssignmentEvidenceCannotBeRewrittenOrErased() {
        var event = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO access_control.staff_provisioning_role_events
                    (event_id, actor_user_id, tenant_id, target_user_id, role_code, intent_id, correlation_id, changed)
                VALUES (?, ?, ?, ?, 'SYNTHETIC', ?, ?, true)
                """, event, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        assertThatThrownBy(() -> jdbc.update("UPDATE access_control.staff_provisioning_role_events SET changed = false WHERE event_id = ?", event))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM access_control.staff_provisioning_role_events WHERE event_id = ?", event))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.execute("TRUNCATE access_control.staff_provisioning_role_events"))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void revocationCannotPassAnAlreadyAcquiredProvisioningAuthorityScope() throws Exception {
        var user = UUID.randomUUID();
        var tenant = UUID.randomUUID();
        var role = UUID.randomUUID();
        var assignment = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO access_control.role_definitions
                    (role_id, tenant_id, code, persona, authority_band, mutability)
                VALUES (?, ?, 'SYNTHETIC_MANAGER', 'STAFF', 'MANAGEMENT', 'TENANT_CUSTOM')
                """, role, tenant);
        jdbc.update("INSERT INTO access_control.role_assignments (assignment_id, user_id, tenant_id, persona, role_id) VALUES (?, ?, ?, 'STAFF', ?)",
                assignment, user, tenant, role);
        var ready = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var pidQueue = new ArrayBlockingQueue<Integer>(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var owner = executor.submit(() -> transaction.execute(status -> {
                jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended('orderhub.authorization.staff-provisioning:' || ?::text || ':' || ?::text, 0))",
                        (row, index) -> null, tenant, user);
                ready.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) { throw new IllegalStateException("Synthetic lock owner timeout"); }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
                return null;
            }));
            assertThat(ready.await(8, TimeUnit.SECONDS)).isTrue();
            var revoker = executor.submit(() -> transaction.execute(status -> {
                pidQueue.add(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                return jdbc.update("DELETE FROM access_control.role_assignments WHERE assignment_id = ?", assignment);
            }));
            var pid = pidQueue.poll(5, TimeUnit.SECONDS);
            assertThat(pid).isNotNull();
            org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
                assertThat(revoker.isDone()).isFalse();
                assertThat(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM pg_locks WHERE pid = ? AND locktype = 'advisory' AND NOT granted)",
                        Boolean.class, pid)).isTrue();
            });
            release.countDown();
            owner.get(12, TimeUnit.SECONDS);
            assertThat(revoker.get(12, TimeUnit.SECONDS)).isEqualTo(1);
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(20, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void productionRoleAssignmentAndEvidenceRollBackTogetherAndPermitRetry() {
        var tenant = UUID.randomUUID();
        var actorId = UUID.randomUUID();
        var targetId = UUID.randomUUID();
        var intent = UUID.randomUUID();
        var correlation = UUID.randomUUID();
        var actorPermissions = Set.of(PermissionCode.TENANT_MEMBERS_MANAGE, PermissionCode.TENANT_ROLES_ASSIGN,
                PermissionCode.INVENTORY_VIEW);
        var targetPermissions = Set.of(PermissionCode.INVENTORY_VIEW);
        var manager = createRole(tenant, "TEST_MANAGER", "MANAGEMENT", actorPermissions);
        createRole(tenant, "TEST_OPERATOR", "OPERATIONAL", targetPermissions);
        jdbc.update("INSERT INTO access_control.role_assignments (assignment_id, user_id, tenant_id, persona, role_id) VALUES (?, ?, ?, 'STAFF', ?)",
                UUID.randomUUID(), actorId, tenant, manager);
        var actor = new StaffProvisioningActor(actorId, tenant, AuthorityBand.MANAGEMENT, PermissionEnvelope.of(actorPermissions));
        var target = new StaffProvisioningTarget(AuthorityBand.OPERATIONAL, PermissionEnvelope.of(targetPermissions));
        var service = service();
        assertThatThrownBy(() -> transaction.execute(status -> {
            service.assign(actor, target, targetId, "TEST_OPERATOR", intent, correlation);
            throw new IllegalStateException("Synthetic outer rollback");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM access_control.role_assignments WHERE user_id = ? AND tenant_id = ?",
                Integer.class, targetId, tenant)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM access_control.staff_provisioning_role_events WHERE intent_id = ?",
                Integer.class, intent)).isZero();
        transaction.execute(status -> {
            service.assign(actor, target, targetId, "TEST_OPERATOR", intent, correlation);
            return null;
        });
        assertThat(jdbc.queryForObject("SELECT count(*) FROM access_control.role_assignments WHERE user_id = ? AND tenant_id = ?",
                Integer.class, targetId, tenant)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT changed FROM access_control.staff_provisioning_role_events WHERE intent_id = ?",
                Boolean.class, intent)).isTrue();
    }

    private UUID createRole(UUID tenant, String code, String band, Set<PermissionCode> permissions) {
        var id = UUID.randomUUID();
        jdbc.update("INSERT INTO access_control.role_definitions (role_id, tenant_id, code, persona, authority_band, mutability) VALUES (?, ?, ?, 'STAFF', ?, 'TENANT_CUSTOM')",
                id, tenant, code, band);
        for (var permission : permissions) {
            jdbc.update("INSERT INTO access_control.role_permissions (role_id, permission_code) VALUES (?, ?)", id, permission.name());
        }
        return id;
    }

    private StaffProvisioningAuthorizationUseCase service() {
        return new StaffProvisioningAuthorizationService(new PostgreSqlRoleAssignmentRepository(jdbc),
                new PostgreSqlRoleDefinitionRepository(jdbc), new PostgreSqlUserPermissionOverrideRepository(jdbc),
                new PostgreSqlStaffProvisioningAuthorizationRepository(jdbc), List.of());
    }
}
