package io.github.piresrenan.orderhub.workforce.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.Set;
import java.util.UUID;

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
import io.github.piresrenan.orderhub.workforce.application.port.out.WorkforceAuditPersistenceException;
import io.github.piresrenan.orderhub.workforce.application.port.out.WorkforcePositionChangePersistenceException;
import io.github.piresrenan.orderhub.workforce.application.service.PrivilegedPositionChangeExecutionService;
import io.github.piresrenan.orderhub.workforce.domain.model.WorkforceMutationDecision;

@SpringBootTest
@ContextConfiguration(
        classes = {
            OrderHubApplication.class,
            PostgreSqlTestConfiguration.class
        })
class PrivilegedPositionChangeExecutionIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PrivilegedPositionChangeExecutionService service;

    @BeforeEach
    void cleanWorkforceState() {

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
    }

    @Test
    void commandCannotCarryCallerSuppliedAuthorityClaims() {

        var componentNames =
                Arrays.stream(
                                PrivilegedPositionChangeCommand.class
                                        .getRecordComponents())
                        .map(component -> component.getName())
                        .toList();

        assertThat(componentNames)
                .containsExactly(
                        "actorStaffId",
                        "targetStaffId",
                        "tenantId",
                        "targetPositionId",
                        "actorPrivilegedAuthorizationAllowed",
                        "actorDelegationEnvelope",
                        "auditEventId",
                        "correlationId");

        assertThat(componentNames)
                .doesNotContain(
                        "beforeAuthorityBand",
                        "afterAuthorityBand",
                        "beforePermissionEnvelope",
                        "afterPermissionEnvelope",
                        "fromPositionId");
    }

    @Test
    void authorizedPromotionUsesPersistedPositionFactsAndAuditsCommit() {

        var fixture = createFixture();

        var auditEventId = UUID.randomUUID();
        var correlationId = UUID.randomUUID();

        var decision =
                service.execute(
                        command(
                                fixture,
                                fixture.managementPositionId(),
                                true,
                                auditEventId,
                                correlationId));

        assertThat(decision)
                .isEqualTo(
                        WorkforceMutationDecision.ALLOW);

        assertThat(
                currentPosition(
                        fixture.tenantId(),
                        fixture.targetStaffId()))
                .isEqualTo(
                        fixture.managementPositionId());

        var audit =
                jdbcTemplate.queryForMap(
                        """
                        SELECT
                            tenant_id,
                            actor_staff_id,
                            affected_staff_id,
                            action_type,
                            outcome,
                            reason_code,
                            correlation_id,
                            before_position_id,
                            after_position_id,
                            before_authority_band,
                            after_authority_band
                        FROM workforce.audit_events
                        WHERE audit_event_id = ?
                        """,
                        auditEventId);

        assertThat(audit.get("tenant_id"))
                .isEqualTo(
                        fixture.tenantId());

        assertThat(audit.get("actor_staff_id"))
                .isEqualTo(
                        fixture.actorStaffId());

        assertThat(audit.get("affected_staff_id"))
                .isEqualTo(
                        fixture.targetStaffId());

        assertThat(audit.get("action_type"))
                .isEqualTo(
                        "POSITION_AUTHORITY_CHANGED");

        assertThat(audit.get("outcome"))
                .isEqualTo(
                        "APPLIED");

        assertThat(audit.get("reason_code"))
                .isNull();

        assertThat(audit.get("correlation_id"))
                .isEqualTo(
                        correlationId);

        assertThat(audit.get("before_position_id"))
                .isEqualTo(
                        fixture.operationalPositionId());

        assertThat(audit.get("after_position_id"))
                .isEqualTo(
                        fixture.managementPositionId());

        assertThat(audit.get("before_authority_band"))
                .isEqualTo(
                        "OPERATIONAL");

        assertThat(audit.get("after_authority_band"))
                .isEqualTo(
                        "MANAGEMENT");
    }

    @Test
    void deniedPrivilegedMutationDoesNotChangePlacementAndPersistsDeniedOutcome() {

        var fixture = createFixture();

        var auditEventId = UUID.randomUUID();

        var decision =
                service.execute(
                        command(
                                fixture,
                                fixture.managementPositionId(),
                                false,
                                auditEventId,
                                UUID.randomUUID()));

        assertThat(decision)
                .isEqualTo(
                        WorkforceMutationDecision.DENY);

        assertThat(
                currentPosition(
                        fixture.tenantId(),
                        fixture.targetStaffId()))
                .isEqualTo(
                        fixture.operationalPositionId());

        var audit =
                jdbcTemplate.queryForMap(
                        """
                        SELECT
                            action_type,
                            outcome,
                            reason_code,
                            before_position_id,
                            after_position_id,
                            before_authority_band,
                            after_authority_band
                        FROM workforce.audit_events
                        WHERE audit_event_id = ?
                        """,
                        auditEventId);

        assertThat(audit.get("action_type"))
                .isEqualTo(
                        "PRIVILEGED_MUTATION");

        assertThat(audit.get("outcome"))
                .isEqualTo(
                        "DENIED");

        assertThat(audit.get("reason_code"))
                .isEqualTo(
                        "PRIVILEGED_POLICY_DENIED");

        assertThat(audit.get("before_position_id"))
                .isEqualTo(
                        fixture.operationalPositionId());

        assertThat(audit.get("after_position_id"))
                .isEqualTo(
                        fixture.operationalPositionId());

        assertThat(audit.get("before_authority_band"))
                .isEqualTo(
                        "OPERATIONAL");

        assertThat(audit.get("after_authority_band"))
                .isEqualTo(
                        "OPERATIONAL");
    }

    @Test
    void crossTenantTargetPositionFailsClosedWithoutMutationOrAudit() {

        var fixture = createFixture();

        var otherTenantId = UUID.randomUUID();
        var otherPositionId = UUID.randomUUID();

        insertPosition(
                otherPositionId,
                otherTenantId,
                "OTHER-MANAGEMENT",
                "Other Management",
                "MANAGEMENT",
                PermissionCode.CATALOG_VIEW,
                PermissionCode.CATALOG_MANAGE);

        var auditEventId = UUID.randomUUID();

        assertThatThrownBy(() ->
                service.execute(
                        command(
                                fixture,
                                otherPositionId,
                                true,
                                auditEventId,
                                UUID.randomUUID())))
                .isInstanceOf(
                        WorkforcePositionChangePersistenceException.class);

        assertThat(
                currentPosition(
                        fixture.tenantId(),
                        fixture.targetStaffId()))
                .isEqualTo(
                        fixture.operationalPositionId());

        assertThat(
                auditCount(
                        auditEventId))
                .isZero();
    }

    @Test
    void auditAppendFailureRollsBackThePersistedPositionChange() {

        var fixture = createFixture();

        var auditEventId = UUID.randomUUID();

        insertPreexistingAudit(
                auditEventId,
                fixture);

        assertThatThrownBy(() ->
                service.execute(
                        command(
                                fixture,
                                fixture.managementPositionId(),
                                true,
                                auditEventId,
                                UUID.randomUUID())))
                .isInstanceOf(
                        WorkforceAuditPersistenceException.class);

        assertThat(
                currentPosition(
                        fixture.tenantId(),
                        fixture.targetStaffId()))
                .isEqualTo(
                        fixture.operationalPositionId());

        assertThat(
                auditCount(
                        auditEventId))
                .isEqualTo(1);
    }

    private PrivilegedPositionChangeCommand command(
            Fixture fixture,
            UUID targetPositionId,
            boolean upstreamAuthorizationAllowed,
            UUID auditEventId,
            UUID correlationId) {

        return new PrivilegedPositionChangeCommand(
                fixture.actorStaffId(),
                fixture.targetStaffId(),
                fixture.tenantId(),
                targetPositionId,
                upstreamAuthorizationAllowed,
                PermissionEnvelope.of(
                        Set.of(
                                PermissionCode.CATALOG_VIEW,
                                PermissionCode.CATALOG_MANAGE)),
                auditEventId,
                correlationId);
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

        var count =
                jdbcTemplate.queryForObject(
                        """
                        SELECT COUNT(*)
                        FROM workforce.audit_events
                        WHERE audit_event_id = ?
                        """,
                        Integer.class,
                        auditEventId);

        return count == null ? 0 : count;
    }

    private void insertPreexistingAudit(
            UUID auditEventId,
            Fixture fixture) {

        jdbcTemplate.update(
                """
                INSERT INTO workforce.audit_events (
                    audit_event_id,
                    tenant_id,
                    actor_staff_id,
                    affected_staff_id,
                    action_type,
                    outcome,
                    reason_code,
                    correlation_id
                )
                VALUES (
                    ?, ?, ?, ?,
                    'PRIVILEGED_MUTATION',
                    'DENIED',
                    'PREEXISTING_EVENT',
                    ?
                )
                """,
                auditEventId,
                fixture.tenantId(),
                fixture.actorStaffId(),
                fixture.targetStaffId(),
                UUID.randomUUID());
    }

    private record Fixture(
            UUID tenantId,
            UUID actorStaffId,
            UUID targetStaffId,
            UUID operationalPositionId,
            UUID managementPositionId) {
    }
}
