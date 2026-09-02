package io.github.piresrenan.orderhub.authorization.adapter.out.persistence.postgresql;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import io.github.piresrenan.orderhub.authorization.application.port.out.RoleAssignmentRepository;
import io.github.piresrenan.orderhub.authorization.application.service.DurableTenantAuthorizationService;
import io.github.piresrenan.orderhub.authorization.domain.model.AuthorizationDecision;
import io.github.piresrenan.orderhub.authorization.domain.model.AuthorizationPersona;
import io.github.piresrenan.orderhub.authorization.domain.model.PermissionCode;
import io.github.piresrenan.orderhub.authorization.domain.model.PermissionEnvelope;
import io.github.piresrenan.orderhub.authorization.domain.model.RoleAssignment;
import io.github.piresrenan.orderhub.authorization.domain.model.TenantAuthorizationRequest;
import io.github.piresrenan.orderhub.authorization.domain.model.TenantAuthorizationScope;

@Testcontainers
class PostgreSqlAuthorizationConcurrencyTest {

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

    private static DataSource dataSource;

    private static JdbcTemplate jdbcTemplate;

    @BeforeAll
    static void migrateSchema() {

        dataSource =
                new DriverManagerDataSource(
                        POSTGRES.getJdbcUrl(),
                        POSTGRES.getUsername(),
                        POSTGRES.getPassword());

        Flyway.configure()
                .dataSource(
                        dataSource)
                .locations(
                        "classpath:db/migration")
                .load()
                .migrate();

        jdbcTemplate =
                new JdbcTemplate(
                        dataSource);
    }

    @BeforeEach
    void cleanAuthorizationState() {

        jdbcTemplate.update(
                """
                TRUNCATE TABLE
                    access_control.user_permission_overrides,
                    access_control.role_assignments,
                    access_control.role_permissions,
                    access_control.role_definitions,
                    access_control.role_code_registry
                CASCADE
                """);
    }

    @Test
    void authorizationDecisionNeverCombinesMutuallyImpossiblePrivilegeSnapshots()
            throws Exception {

        var userId =
                UUID.randomUUID();

        var tenantId =
                UUID.randomUUID();

        var scope =
                new TenantAuthorizationScope(
                        tenantId);

        var roleId =
                UUID.randomUUID();

        jdbcTemplate.update(
                """
                INSERT INTO access_control.role_definitions (
                    role_id,
                    tenant_id,
                    code,
                    persona,
                    authority_band,
                    mutability
                )
                VALUES (
                    ?,
                    NULL,
                    'CONCURRENCY_PROBE_ROLE',
                    'STAFF',
                    'OPERATIONAL',
                    'BUILTIN_FUNCTIONAL'
                )
                """,
                roleId);

        var durableAssignments =
                new PostgreSqlRoleAssignmentRepository(
                        jdbcTemplate);

        durableAssignments.save(
                new RoleAssignment(
                        userId,
                        AuthorizationPersona.STAFF,
                        scope,
                        "CONCURRENCY_PROBE_ROLE"));

        var assignmentObserved =
                new CountDownLatch(
                        1);

        var mutationCommitted =
                new CountDownLatch(
                        1);

        RoleAssignmentRepository latchingAssignments =
                new RoleAssignmentRepository() {

                    @Override
                    public void save(
                            RoleAssignment assignment) {

                        durableAssignments.save(
                                assignment);
                    }

                    @Override
                    public List<RoleAssignment> findByUserIdAndScope(
                            UUID requestedUserId,
                            TenantAuthorizationScope requestedScope) {

                        var result =
                                durableAssignments
                                        .findByUserIdAndScope(
                                                requestedUserId,
                                                requestedScope);

                        assignmentObserved.countDown();

                        await(
                                mutationCommitted);

                        return result;
                    }
                };

        var transactionBoundary =
                new PostgreSqlAuthorizationDecisionReadTransaction(
                        new JdbcTransactionManager(
                                dataSource));

        var service =
                new DurableTenantAuthorizationService(
                        latchingAssignments,
                        new PostgreSqlRoleDefinitionRepository(
                                jdbcTemplate),
                        new PostgreSqlUserPermissionOverrideRepository(
                                jdbcTemplate),
                        transactionBoundary,
                        observation -> {
                        });

        var request =
                new TenantAuthorizationRequest(
                        userId,
                        AuthorizationPersona.STAFF,
                        scope,
                        PermissionCode.INVENTORY_ADJUST);

        var decision =
                CompletableFuture.supplyAsync(
                        () ->
                                service.authorize(
                                        request,
                                        PermissionEnvelope.of(
                                                Set.of(
                                                        PermissionCode.INVENTORY_ADJUST))));

        assertThat(
                assignmentObserved.await(
                        5,
                        TimeUnit.SECONDS))
                .as(
                        "authorization must establish its durable snapshot before the competing mutation")
                .isTrue();

        /*
         * This single competing logical privilege change transforms:
         *
         * before:
         *   assignment exists + permission absent -> DENY
         *
         * after:
         *   assignment absent + permission exists -> DENY
         *
         * A mixed snapshot is the only state capable of producing ALLOW.
         */
        jdbcTemplate.update(
                """
                DELETE FROM access_control.role_assignments
                WHERE user_id = ?
                  AND tenant_id = ?
                """,
                userId,
                tenantId);

        jdbcTemplate.update(
                """
                INSERT INTO access_control.role_permissions (
                    role_id,
                    permission_code
                )
                VALUES (?, 'INVENTORY_ADJUST')
                """,
                roleId);

        mutationCommitted.countDown();

        assertThat(
                decision.get(
                        5,
                        TimeUnit.SECONDS))
                .isEqualTo(
                        AuthorizationDecision.DENY);

        assertThat(
                jdbcTemplate.queryForObject(
                        """
                        SELECT COUNT(*)
                        FROM access_control.role_assignments
                        WHERE user_id = ?
                          AND tenant_id = ?
                        """,
                        Integer.class,
                        userId,
                        tenantId))
                .isZero();

        assertThat(
                jdbcTemplate.queryForObject(
                        """
                        SELECT COUNT(*)
                        FROM access_control.role_permissions
                        WHERE role_id = ?
                          AND permission_code = 'INVENTORY_ADJUST'
                        """,
                        Integer.class,
                        roleId))
                .isEqualTo(
                        1);
    }

    @Test
    void outerReadCommittedTransactionCannotDowngradeAuthorizationSnapshot()
            throws Exception {

        var userId =
                UUID.randomUUID();

        var tenantId =
                UUID.randomUUID();

        var scope =
                new TenantAuthorizationScope(
                        tenantId);

        var roleId =
                UUID.randomUUID();

        jdbcTemplate.update(
                """
                INSERT INTO access_control.role_definitions (
                    role_id,
                    tenant_id,
                    code,
                    persona,
                    authority_band,
                    mutability
                )
                VALUES (
                    ?,
                    NULL,
                    'NESTED_CONCURRENCY_PROBE_ROLE',
                    'STAFF',
                    'OPERATIONAL',
                    'BUILTIN_FUNCTIONAL'
                )
                """,
                roleId);

        var durableAssignments =
                new PostgreSqlRoleAssignmentRepository(
                        jdbcTemplate);

        durableAssignments.save(
                new RoleAssignment(
                        userId,
                        AuthorizationPersona.STAFF,
                        scope,
                        "NESTED_CONCURRENCY_PROBE_ROLE"));

        var assignmentObserved =
                new CountDownLatch(
                        1);

        var mutationCommitted =
                new CountDownLatch(
                        1);

        RoleAssignmentRepository latchingAssignments =
                new RoleAssignmentRepository() {

                    @Override
                    public void save(
                            RoleAssignment assignment) {

                        durableAssignments.save(
                                assignment);
                    }

                    @Override
                    public List<RoleAssignment> findByUserIdAndScope(
                            UUID requestedUserId,
                            TenantAuthorizationScope requestedScope) {

                        var result =
                                durableAssignments
                                        .findByUserIdAndScope(
                                                requestedUserId,
                                                requestedScope);

                        assignmentObserved.countDown();

                        await(
                                mutationCommitted);

                        return result;
                    }
                };

        var transactionManager =
                new JdbcTransactionManager(
                        dataSource);

        var authorizationTransaction =
                new PostgreSqlAuthorizationDecisionReadTransaction(
                        transactionManager);

        var service =
                new DurableTenantAuthorizationService(
                        latchingAssignments,
                        new PostgreSqlRoleDefinitionRepository(
                                jdbcTemplate),
                        new PostgreSqlUserPermissionOverrideRepository(
                                jdbcTemplate),
                        authorizationTransaction,
                        observation -> {
                        });

        var request =
                new TenantAuthorizationRequest(
                        userId,
                        AuthorizationPersona.STAFF,
                        scope,
                        PermissionCode.INVENTORY_ADJUST);

        var outerTransaction =
                new TransactionTemplate(
                        transactionManager);

        outerTransaction.setPropagationBehavior(
                TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        outerTransaction.setIsolationLevel(
                TransactionDefinition.ISOLATION_READ_COMMITTED);

        var decision =
                CompletableFuture.supplyAsync(
                        () ->
                                outerTransaction.execute(
                                        status ->
                                                service.authorize(
                                                        request,
                                                        PermissionEnvelope.of(
                                                                Set.of(
                                                                        PermissionCode.INVENTORY_ADJUST)))));

        assertThat(
                assignmentObserved.await(
                        5,
                        TimeUnit.SECONDS))
                .as(
                        "authorization must complete its first durable read before the competing mutation")
                .isTrue();

        jdbcTemplate.update(
                """
                DELETE FROM access_control.role_assignments
                WHERE user_id = ?
                  AND tenant_id = ?
                """,
                userId,
                tenantId);

        jdbcTemplate.update(
                """
                INSERT INTO access_control.role_permissions (
                    role_id,
                    permission_code
                )
                VALUES (?, 'INVENTORY_ADJUST')
                """,
                roleId);

        mutationCommitted.countDown();

        assertThat(
                decision.get(
                        5,
                        TimeUnit.SECONDS))
                .as(
                        "an outer READ COMMITTED transaction must not downgrade the authorization snapshot")
                .isEqualTo(
                        AuthorizationDecision.DENY);
    }
    @Test
    void concurrentSystemAndTenantCustomRoleCodeReservationHasOnlyOneWinner()
            throws Exception {

        var roleCode =
                "RACE_ROLE_"
                        + UUID.randomUUID()
                                .toString()
                                .replace(
                                        "-",
                                        "")
                                .toUpperCase();

        var start =
                new CountDownLatch(
                        1);

        var systemInsert =
                CompletableFuture.supplyAsync(
                        () ->
                                attemptRoleInsert(
                                        start,
                                        null,
                                        roleCode,
                                        "BUILTIN_FUNCTIONAL"));

        var tenantCustomInsert =
                CompletableFuture.supplyAsync(
                        () ->
                                attemptRoleInsert(
                                        start,
                                        UUID.randomUUID(),
                                        roleCode,
                                        "TENANT_CUSTOM"));

        start.countDown();

        var systemWon =
                systemInsert.get(
                        5,
                        TimeUnit.SECONDS);

        var customWon =
                tenantCustomInsert.get(
                        5,
                        TimeUnit.SECONDS);

        assertThat(
                List.of(
                        systemWon,
                        customWon))
                .containsExactlyInAnyOrder(
                        true,
                        false);

        assertThat(
                jdbcTemplate.queryForObject(
                        """
                        SELECT COUNT(*)
                        FROM access_control.role_definitions
                        WHERE code = ?
                        """,
                        Integer.class,
                        roleCode))
                .isEqualTo(
                        1);

        var persistedMutability =
                jdbcTemplate.queryForObject(
                        """
                        SELECT mutability
                        FROM access_control.role_definitions
                        WHERE code = ?
                        """,
                        String.class,
                        roleCode);

        var reservedNamespace =
                jdbcTemplate.queryForObject(
                        """
                        SELECT role_namespace
                        FROM access_control.role_code_registry
                        WHERE code = ?
                        """,
                        String.class,
                        roleCode);

        if ("TENANT_CUSTOM".equals(
                persistedMutability)) {

            assertThat(
                    reservedNamespace)
                    .isEqualTo(
                            "TENANT_CUSTOM");

        } else {

            assertThat(
                    persistedMutability)
                    .isEqualTo(
                            "BUILTIN_FUNCTIONAL");

            assertThat(
                    reservedNamespace)
                    .isEqualTo(
                            "SYSTEM");
        }
    }

    private static boolean attemptRoleInsert(
            CountDownLatch start,
            UUID tenantId,
            String code,
            String mutability) {

        await(
                start);

        try {
            jdbcTemplate.update(
                    """
                    INSERT INTO access_control.role_definitions (
                        role_id,
                        tenant_id,
                        code,
                        persona,
                        authority_band,
                        mutability
                    )
                    VALUES (?, ?, ?, 'STAFF', 'OPERATIONAL', ?)
                    """,
                    UUID.randomUUID(),
                    tenantId,
                    code,
                    mutability);

            return true;

        } catch (DataAccessException exception) {

            return false;
        }
    }

    private static void await(
            CountDownLatch latch) {

        try {
            if (!latch.await(
                    5,
                    TimeUnit.SECONDS)) {

                throw new AssertionError(
                        "Timed out waiting for concurrent authorization fixture");
            }

        } catch (InterruptedException exception) {

            Thread.currentThread()
                    .interrupt();

            throw new AssertionError(
                    "Concurrent authorization fixture was interrupted",
                    exception);
        }
    }
}
