package io.github.piresrenan.orderhub.workforce.adapter.out.persistence.postgresql;

import java.util.List;
import java.util.UUID;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import io.github.piresrenan.orderhub.authorization.domain.model.AuthorityBand;
import io.github.piresrenan.orderhub.authorization.domain.model.PermissionCode;
import io.github.piresrenan.orderhub.authorization.domain.model.PermissionEnvelope;
import io.github.piresrenan.orderhub.workforce.application.model.WorkforcePositionChangeSnapshot;
import io.github.piresrenan.orderhub.workforce.application.port.out.WorkforcePositionChangePersistenceException;
import io.github.piresrenan.orderhub.workforce.application.port.out.WorkforcePositionChangeRepository;
import io.github.piresrenan.orderhub.workforce.domain.model.JobPosition;
import io.github.piresrenan.orderhub.workforce.domain.model.StaffProfile;
import io.github.piresrenan.orderhub.workforce.domain.model.StaffStatus;

/**
 * PostgreSQL authority for resolving and changing current Staff position.
 *
 * <p>
 * Reads lock the concrete Staff, placement, JobPosition and existing position
 * permission rows required by the authorization decision. Position mutation also
 * compares the expected current position so stale concurrent decisions fail
 * instead of silently overwriting a newer placement.
 * </p>
 */
public final class PostgreSqlWorkforcePositionChangeRepository
        implements WorkforcePositionChangeRepository {

    private final JdbcTemplate jdbcTemplate;

    public PostgreSqlWorkforcePositionChangeRepository(
            JdbcTemplate jdbcTemplate) {

        if (jdbcTemplate == null) {
            throw new IllegalArgumentException(
                    "JdbcTemplate is required");
        }

        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public WorkforcePositionChangeSnapshot loadForUpdate(
            UUID tenantId,
            UUID actorStaffId,
            UUID targetStaffId,
            UUID targetPositionId) {

        requireIdentifier(
                tenantId,
                "Tenant ID");

        requireIdentifier(
                actorStaffId,
                "Actor Staff ID");

        requireIdentifier(
                targetStaffId,
                "Target Staff ID");

        requireIdentifier(
                targetPositionId,
                "Target position ID");

        try {
            var staffProfiles =
                    lockStaffProfiles(
                            tenantId,
                            actorStaffId,
                            targetStaffId);

            var actor =
                    requireStaff(
                            staffProfiles,
                            actorStaffId,
                            "Actor Staff");

            var target =
                    requireStaff(
                            staffProfiles,
                            targetStaffId,
                            "Target Staff");

            var placements =
                    lockPlacements(
                            tenantId,
                            actorStaffId,
                            targetStaffId);

            var actorPlacement =
                    requirePlacement(
                            placements,
                            actorStaffId,
                            "Actor placement");

            var targetPlacement =
                    requirePlacement(
                            placements,
                            targetStaffId,
                            "Target placement");

            var positionRows =
                    lockPositions(
                            tenantId,
                            actorPlacement.positionId(),
                            targetPlacement.positionId(),
                            targetPositionId);

            var actorPosition =
                    toJobPosition(
                            tenantId,
                            requirePosition(
                                    positionRows,
                                    actorPlacement.positionId(),
                                    "Actor position"));

            var currentTargetPosition =
                    toJobPosition(
                            tenantId,
                            requirePosition(
                                    positionRows,
                                    targetPlacement.positionId(),
                                    "Current target position"));

            var requestedTargetPosition =
                    toJobPosition(
                            tenantId,
                            requirePosition(
                                    positionRows,
                                    targetPositionId,
                                    "Requested target position"));

            return new WorkforcePositionChangeSnapshot(
                    actor,
                    target,
                    targetPlacement.departmentId(),
                    actorPosition,
                    currentTargetPosition,
                    requestedTargetPosition);

        } catch (DataAccessException | IllegalArgumentException exception) {

            throw new WorkforcePositionChangePersistenceException(
                    "Failed to resolve authoritative workforce position state",
                    exception);
        }
    }

    @Override
    public void changePosition(
            UUID tenantId,
            UUID targetStaffId,
            UUID expectedCurrentPositionId,
            UUID targetPositionId) {

        requireIdentifier(
                tenantId,
                "Tenant ID");

        requireIdentifier(
                targetStaffId,
                "Target Staff ID");

        requireIdentifier(
                expectedCurrentPositionId,
                "Expected current position ID");

        requireIdentifier(
                targetPositionId,
                "Target position ID");

        try {
            var updated =
                    jdbcTemplate.update(
                            """
                            UPDATE workforce.staff_placements
                            SET position_id = ?
                            WHERE tenant_id = ?
                              AND staff_id = ?
                              AND position_id = ?
                            """,
                            targetPositionId,
                            tenantId,
                            targetStaffId,
                            expectedCurrentPositionId);

            if (updated != 1) {
                throw new WorkforcePositionChangePersistenceException(
                        "Workforce placement changed concurrently");
            }

        } catch (DataAccessException exception) {

            throw new WorkforcePositionChangePersistenceException(
                    "Failed to persist workforce position change",
                    exception);
        }
    }

    private List<StaffProfile> lockStaffProfiles(
            UUID tenantId,
            UUID actorStaffId,
            UUID targetStaffId) {

        return jdbcTemplate.query(
                """
                SELECT
                    staff_id,
                    user_id,
                    tenant_id,
                    status
                FROM workforce.staff_profiles
                WHERE tenant_id = ?
                  AND staff_id IN (?, ?)
                ORDER BY staff_id
                FOR UPDATE
                """,
                (resultSet, rowNumber) ->
                        new StaffProfile(
                                resultSet.getObject(
                                        "staff_id",
                                        UUID.class),
                                resultSet.getObject(
                                        "user_id",
                                        UUID.class),
                                resultSet.getObject(
                                        "tenant_id",
                                        UUID.class),
                                StaffStatus.valueOf(
                                        resultSet.getString(
                                                "status"))),
                tenantId,
                actorStaffId,
                targetStaffId);
    }

    private List<PlacementRow> lockPlacements(
            UUID tenantId,
            UUID actorStaffId,
            UUID targetStaffId) {

        return jdbcTemplate.query(
                """
                SELECT
                    staff_id,
                    department_id,
                    position_id
                FROM workforce.staff_placements
                WHERE tenant_id = ?
                  AND staff_id IN (?, ?)
                ORDER BY staff_id
                FOR UPDATE
                """,
                (resultSet, rowNumber) ->
                        new PlacementRow(
                                resultSet.getObject(
                                        "staff_id",
                                        UUID.class),
                                resultSet.getObject(
                                        "department_id",
                                        UUID.class),
                                resultSet.getObject(
                                        "position_id",
                                        UUID.class)),
                tenantId,
                actorStaffId,
                targetStaffId);
    }

    private List<PositionRow> lockPositions(
            UUID tenantId,
            UUID actorPositionId,
            UUID currentTargetPositionId,
            UUID requestedTargetPositionId) {

        return jdbcTemplate.query(
                """
                SELECT
                    position_id,
                    code,
                    title,
                    authority_band
                FROM workforce.job_positions
                WHERE tenant_id = ?
                  AND position_id IN (?, ?, ?)
                ORDER BY position_id
                FOR UPDATE
                """,
                (resultSet, rowNumber) ->
                        new PositionRow(
                                resultSet.getObject(
                                        "position_id",
                                        UUID.class),
                                resultSet.getString(
                                        "code"),
                                resultSet.getString(
                                        "title"),
                                AuthorityBand.valueOf(
                                        resultSet.getString(
                                                "authority_band"))),
                tenantId,
                actorPositionId,
                currentTargetPositionId,
                requestedTargetPositionId);
    }

    private JobPosition toJobPosition(
            UUID tenantId,
            PositionRow row) {

        var permissions =
                jdbcTemplate.queryForList(
                                """
                                SELECT permission_code
                                FROM workforce.job_position_permissions
                                WHERE tenant_id = ?
                                  AND position_id = ?
                                ORDER BY permission_code
                                FOR UPDATE
                                """,
                                String.class,
                                tenantId,
                                row.positionId())
                        .stream()
                        .map(PermissionCode::valueOf)
                        .toList();

        return new JobPosition(
                row.positionId(),
                tenantId,
                row.code(),
                row.title(),
                row.authorityBand(),
                PermissionEnvelope.of(
                        permissions));
    }

    private StaffProfile requireStaff(
            List<StaffProfile> staffProfiles,
            UUID staffId,
            String label) {

        return staffProfiles.stream()
                .filter(staff ->
                        staff.staffId().equals(
                                staffId))
                .findFirst()
                .orElseThrow(() ->
                        new WorkforcePositionChangePersistenceException(
                                label + " does not exist in Tenant"));
    }

    private PlacementRow requirePlacement(
            List<PlacementRow> placements,
            UUID staffId,
            String label) {

        return placements.stream()
                .filter(placement ->
                        placement.staffId().equals(
                                staffId))
                .findFirst()
                .orElseThrow(() ->
                        new WorkforcePositionChangePersistenceException(
                                label + " does not exist in Tenant"));
    }

    private PositionRow requirePosition(
            List<PositionRow> positions,
            UUID positionId,
            String label) {

        return positions.stream()
                .filter(position ->
                        position.positionId().equals(
                                positionId))
                .findFirst()
                .orElseThrow(() ->
                        new WorkforcePositionChangePersistenceException(
                                label + " does not exist in Tenant"));
    }

    private static void requireIdentifier(
            UUID value,
            String label) {

        if (value == null) {
            throw new IllegalArgumentException(
                    label + " is required");
        }
    }

    private record PlacementRow(
            UUID staffId,
            UUID departmentId,
            UUID positionId) {
    }

    private record PositionRow(
            UUID positionId,
            String code,
            String title,
            AuthorityBand authorityBand) {
    }
}
