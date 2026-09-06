package io.github.piresrenan.orderhub.workforce.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.util.Set;
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;

import io.github.piresrenan.orderhub.OrderHubApplication;
import io.github.piresrenan.orderhub.authorization.domain.model.PermissionCode;
import io.github.piresrenan.orderhub.authorization.domain.model.PermissionEnvelope;
import io.github.piresrenan.orderhub.support.PostgreSqlTestConfiguration;
import io.github.piresrenan.orderhub.workforce.application.model.PrivilegedPositionChangeCommand;
import io.github.piresrenan.orderhub.workforce.application.port.out.WorkforceTransactionExecutor;
import io.github.piresrenan.orderhub.workforce.application.service.PrivilegedPositionChangeExecutionService;

/**
 * Proves that a real failure to record durable delivery intent prevents the
 * workforce source transaction from committing.
 *
 * <p>
 * The failure is produced by the actual publication path: the real production
 * listener is present, so the persistent multicaster really does insert into
 * {@code public.event_publication} while the source transaction is open. A
 * separate connection holds an {@code ACCESS EXCLUSIVE} lock on that relation,
 * so the insert genuinely cannot complete and PostgreSQL ends the attempt
 * through the source transaction's own {@code lock_timeout}.
 * </p>
 *
 * <p>
 * Throwing from a stubbed publisher would prove only that an exception
 * propagates through Java. It would say nothing about whether the selected
 * Spring Modulith JDBC registry actually participates in the source
 * transaction, which is the invariant that makes an unrecoverable committed
 * change impossible.
 * </p>
 *
 * <p>
 * No schema object is created, altered or dropped. The lock is an ordinary
 * PostgreSQL concurrency primitive and is released in every outcome.
 * </p>
 */
@SpringBootTest
@ContextConfiguration(
        classes = {
            OrderHubApplication.class,
            PostgreSqlTestConfiguration.class
        })
class WorkforceAuthorityChangePublicationFailureAtomicityTest {

    /**
     * Bounded wait for the blocked registry insert. It is long enough that a
     * healthy insert would have completed, and short enough to keep the test
     * deterministic rather than dependent on a default statement timeout.
     */
    private static final String SOURCE_LOCK_TIMEOUT = "2s";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private WorkforceTransactionExecutor transactionExecutor;

    @Autowired
    private PrivilegedPositionChangeExecutionService
            privilegedPositionChangeService;

    @BeforeEach
    void cleanWorkforceAndAnalyticalState() {

        jdbcTemplate.execute(
                """
                TRUNCATE TABLE
                    workforce.audit_events,
                    workforce.reporting_relationships,
                    workforce.staff_placements,
                    workforce.job_position_permissions,
                    workforce.job_positions,
                    workforce.departments,
                    workforce.staff_profiles
                CASCADE
                """);

        jdbcTemplate.execute(
                "TRUNCATE TABLE analytics.workforce_authority_change_facts");

        jdbcTemplate.execute(
                "TRUNCATE TABLE analytics.subject_pseudonyms");

        jdbcTemplate.execute(
                "TRUNCATE TABLE public.event_publication");
    }

    @Test
    void registryPersistenceFailureRollsBackTheMutationAndItsAuditEvidence()
            throws Exception {
        // Why: the source transaction may only commit if durable delivery
        // intent was recorded with it. Committing without that record would
        // create an operational authority change whose analytical projection is
        // permanently unrecoverable, because nothing would remain to replay.
        // Covers: a real INSERT into the Spring Modulith publication registry
        // failing inside an open workforce transaction, and the whole source
        // transaction unwinding as one unit.
        // Prevents: an operational change surviving a lost publication, a
        // half-written audit trail, and an analytical fact for an event whose
        // source never committed.
        //
        // The lock is taken before the source transaction starts, so the
        // failure point is the registry insert rather than the fixture.

        var fixture =
                createFixture();

        var auditEventId =
                UUID.randomUUID();

        try (var lockingConnection =
                        dataSource.getConnection()) {

            lockRegistryExclusively(
                    lockingConnection);

            assertThatThrownBy(() ->
                    transactionExecutor.execute(() -> {

                        jdbcTemplate.execute(
                                "SET LOCAL lock_timeout = '"
                                        + SOURCE_LOCK_TIMEOUT
                                        + "'");

                        return privilegedPositionChangeService.execute(
                                appliedCommand(
                                        fixture,
                                        auditEventId));
                    }))
                    .as("A registry insert that cannot complete must fail the"
                            + " source transaction rather than be skipped")
                    .isInstanceOf(
                            org.springframework.dao.DataAccessException.class)
                    .as("The failure must come from the framework's own"
                            + " publication repository, so this proves durable"
                            + " registration failed rather than some other"
                            + " blocked statement")
                    .hasStackTraceContaining(
                            "JdbcEventPublicationRepositoryV2")
                    .as("The mechanism must be the PostgreSQL lock timeout the"
                            + " source transaction set. The SQLSTATE is"
                            + " asserted rather than the message, which the"
                            + " server may localise")
                    .hasStackTraceContaining(
                            "55P03");

            lockingConnection.rollback();
        }

        assertThat(
                currentPosition(
                        fixture.tenantId(),
                        fixture.targetStaffId()))
                .as("The operational placement must not survive a source"
                        + " transaction that could not record delivery intent")
                .isEqualTo(
                        fixture.operationalPositionId());

        assertThat(
                auditCount(
                        auditEventId))
                .as("The audit evidence must roll back with the mutation it"
                        + " describes")
                .isZero();

        assertThat(
                publicationCount())
                .as("No durable publication may remain for a source"
                        + " transaction that rolled back")
                .isZero();

        assertThat(
                factCount(
                        fixture.tenantId()))
                .as("No analytical fact may exist for an operation that never"
                        + " committed")
                .isZero();

        assertThat(
                pseudonymCount(
                        fixture.tenantId()))
                .as("The after-commit projection must never have run, so it can"
                        + " have established no analytical subject identity")
                .isZero();
    }

    /**
     * Blocks the publication registry with an ordinary PostgreSQL table lock.
     *
     * <p>
     * {@code ACCESS EXCLUSIVE} is the only mode that conflicts with the
     * registry's own {@code INSERT}, which is what makes the failure real
     * rather than simulated. Autocommit is disabled so the lock is held for the
     * lifetime of the surrounding transaction and released deterministically.
     * </p>
     */
    private void lockRegistryExclusively(
            Connection connection)
            throws Exception {

        connection.setAutoCommit(false);

        try (var statement =
                        connection.createStatement()) {

            statement.execute(
                    """
                    LOCK TABLE public.event_publication
                        IN ACCESS EXCLUSIVE MODE
                    """);
        }
    }

    private PrivilegedPositionChangeCommand appliedCommand(
            Fixture fixture,
            UUID auditEventId) {

        return new PrivilegedPositionChangeCommand(
                fixture.actorStaffId(),
                fixture.targetStaffId(),
                fixture.tenantId(),
                fixture.managementPositionId(),
                true,
                PermissionEnvelope.of(
                        Set.of(
                                PermissionCode.CATALOG_VIEW,
                                PermissionCode.CATALOG_MANAGE)),
                auditEventId,
                UUID.randomUUID());
    }

    private Fixture createFixture() {

        var tenantId = UUID.randomUUID();
        var departmentId = UUID.randomUUID();

        var actorStaffId = UUID.randomUUID();
        var targetStaffId = UUID.randomUUID();

        var governancePositionId = UUID.randomUUID();
        var operationalPositionId = UUID.randomUUID();
        var managementPositionId = UUID.randomUUID();

        insertDepartment(
                departmentId,
                tenantId);

        insertPosition(
                governancePositionId,
                tenantId,
                "TENANT-GOV",
                "Tenant Governance",
                "TENANT_GOVERNANCE",
                PermissionCode.CATALOG_VIEW,
                PermissionCode.CATALOG_MANAGE);

        insertPosition(
                operationalPositionId,
                tenantId,
                "OPS",
                "Operations",
                "OPERATIONAL",
                PermissionCode.CATALOG_VIEW);

        insertPosition(
                managementPositionId,
                tenantId,
                "MANAGEMENT",
                "Management",
                "MANAGEMENT",
                PermissionCode.CATALOG_VIEW,
                PermissionCode.CATALOG_MANAGE);

        insertActiveStaff(
                actorStaffId,
                tenantId);

        insertActiveStaff(
                targetStaffId,
                tenantId);

        insertPlacement(
                tenantId,
                actorStaffId,
                departmentId,
                governancePositionId);

        insertPlacement(
                tenantId,
                targetStaffId,
                departmentId,
                operationalPositionId);

        return new Fixture(
                tenantId,
                actorStaffId,
                targetStaffId,
                operationalPositionId,
                managementPositionId);
    }

    private void insertDepartment(
            UUID departmentId,
            UUID tenantId) {

        jdbcTemplate.update(
                """
                INSERT INTO workforce.departments (
                    department_id,
                    tenant_id,
                    code,
                    name
                )
                VALUES (?, ?, 'OPS', 'Operations')
                """,
                departmentId,
                tenantId);
    }

    private void insertPosition(
            UUID positionId,
            UUID tenantId,
            String code,
            String title,
            String authorityBand,
            PermissionCode... permissions) {

        jdbcTemplate.update(
                """
                INSERT INTO workforce.job_positions (
                    position_id,
                    tenant_id,
                    code,
                    title,
                    authority_band
                )
                VALUES (?, ?, ?, ?, ?)
                """,
                positionId,
                tenantId,
                code,
                title,
                authorityBand);

        for (var permission : permissions) {
            jdbcTemplate.update(
                    """
                    INSERT INTO workforce.job_position_permissions (
                        tenant_id,
                        position_id,
                        permission_code
                    )
                    VALUES (?, ?, ?)
                    """,
                    tenantId,
                    positionId,
                    permission.name());
        }
    }

    private void insertActiveStaff(
            UUID staffId,
            UUID tenantId) {

        jdbcTemplate.update(
                """
                INSERT INTO workforce.staff_profiles (
                    staff_id,
                    user_id,
                    tenant_id,
                    status
                )
                VALUES (?, ?, ?, 'ACTIVE')
                """,
                staffId,
                UUID.randomUUID(),
                tenantId);
    }

    private void insertPlacement(
            UUID tenantId,
            UUID staffId,
            UUID departmentId,
            UUID positionId) {

        jdbcTemplate.update(
                """
                INSERT INTO workforce.staff_placements (
                    tenant_id,
                    staff_id,
                    department_id,
                    position_id
                )
                VALUES (?, ?, ?, ?)
                """,
                tenantId,
                staffId,
                departmentId,
                positionId);
    }

    private UUID currentPosition(
            UUID tenantId,
            UUID staffId) {

        return jdbcTemplate.queryForObject(
                """
                SELECT position_id
                FROM workforce.staff_placements
                WHERE tenant_id = ?
                  AND staff_id = ?
                """,
                UUID.class,
                tenantId,
                staffId);
    }

    private int auditCount(
            UUID auditEventId) {

        return count(
                """
                SELECT count(*)
                FROM workforce.audit_events
                WHERE audit_event_id = ?
                """,
                auditEventId);
    }

    private int publicationCount() {

        return count(
                """
                SELECT count(*)
                FROM public.event_publication
                """);
    }

    private int factCount(
            UUID tenantId) {

        return count(
                """
                SELECT count(*)
                FROM analytics.workforce_authority_change_facts
                WHERE tenant_id = ?
                """,
                tenantId);
    }

    private int pseudonymCount(
            UUID tenantId) {

        return count(
                """
                SELECT count(*)
                FROM analytics.subject_pseudonyms
                WHERE tenant_id = ?
                """,
                tenantId);
    }

    private int count(
            String sql,
            Object... arguments) {

        var value =
                jdbcTemplate.queryForObject(
                        sql,
                        Integer.class,
                        arguments);

        return value == null ? 0 : value;
    }

    private record Fixture(
            UUID tenantId,
            UUID actorStaffId,
            UUID targetStaffId,
            UUID operationalPositionId,
            UUID managementPositionId) {
    }
}
