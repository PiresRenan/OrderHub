package io.github.piresrenan.orderhub.authorization.adapter.out.persistence.postgresql;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import io.github.piresrenan.orderhub.authorization.application.port.out.AuthorizationPersistenceException;
import io.github.piresrenan.orderhub.authorization.application.port.out.RoleAssignmentRepository;
import io.github.piresrenan.orderhub.authorization.domain.model.AuthorizationPersona;
import io.github.piresrenan.orderhub.authorization.domain.model.RoleAssignment;
import io.github.piresrenan.orderhub.authorization.domain.model.TenantAuthorizationScope;

/**
 * PostgreSQL adapter for Tenant-scoped role assignments.
 */
public final class PostgreSqlRoleAssignmentRepository
        implements RoleAssignmentRepository {

    private static final String RESOLVE_ROLE_ID_SQL = """
            SELECT
                role_id
            FROM access_control.role_definitions
            WHERE code = ?
              AND (
                    tenant_id = ?
                    OR tenant_id IS NULL
              )
            ORDER BY
                CASE
                    WHEN tenant_id = ? THEN 0
                    ELSE 1
                END
            LIMIT 1
            """;

    private static final String INSERT_ASSIGNMENT_SQL = """
            INSERT INTO access_control.role_assignments (
                assignment_id,
                user_id,
                tenant_id,
                persona,
                role_id
            )
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT (
                user_id,
                tenant_id,
                persona,
                role_id
            )
            DO NOTHING
            """;

    private static final String FIND_ASSIGNMENTS_SQL = """
            SELECT
                assignment.user_id,
                assignment.tenant_id,
                assignment.persona,
                role.code AS role_code
            FROM access_control.role_assignments assignment
            JOIN access_control.role_definitions role
              ON role.role_id = assignment.role_id
            WHERE assignment.user_id = ?
              AND assignment.tenant_id = ?
            ORDER BY role.code
            """;

    private final JdbcTemplate jdbcTemplate;

    public PostgreSqlRoleAssignmentRepository(
            JdbcTemplate jdbcTemplate) {

        this.jdbcTemplate =
                Objects.requireNonNull(
                        jdbcTemplate,
                        "jdbcTemplate");
    }

    @Override
    public void save(
            RoleAssignment assignment) {

        Objects.requireNonNull(
                assignment,
                "assignment");

        try {
            var roleId =
                    resolveRoleId(
                            assignment);

            jdbcTemplate.update(
                    INSERT_ASSIGNMENT_SQL,
                    UUID.randomUUID(),
                    assignment.userId(),
                    assignment.scope()
                            .tenantId(),
                    assignment.persona()
                            .name(),
                    roleId);

        } catch (DataAccessException exception) {
            throw new AuthorizationPersistenceException(
                    exception);
        }
    }

    @Override
    public List<RoleAssignment> findByUserIdAndScope(
            UUID userId,
            TenantAuthorizationScope scope) {

        Objects.requireNonNull(
                userId,
                "userId");

        Objects.requireNonNull(
                scope,
                "scope");

        try {
            return List.copyOf(
                    jdbcTemplate.query(
                            FIND_ASSIGNMENTS_SQL,
                            (resultSet, rowNumber) ->
                                    new RoleAssignment(
                                            resultSet.getObject(
                                                    "user_id",
                                                    UUID.class),
                                            AuthorizationPersona.valueOf(
                                                    resultSet.getString(
                                                            "persona")),
                                            new TenantAuthorizationScope(
                                                    resultSet.getObject(
                                                            "tenant_id",
                                                            UUID.class)),
                                            resultSet.getString(
                                                    "role_code")),
                            userId,
                            scope.tenantId()));

        } catch (DataAccessException exception) {
            throw new AuthorizationPersistenceException(
                    exception);
        }
    }

    private UUID resolveRoleId(
            RoleAssignment assignment) {

        var tenantId =
                assignment.scope()
                        .tenantId();

        var roleIds =
                jdbcTemplate.query(
                        RESOLVE_ROLE_ID_SQL,
                        (resultSet, rowNumber) ->
                                resultSet.getObject(
                                        "role_id",
                                        UUID.class),
                        assignment.roleCode(),
                        tenantId,
                        tenantId);

        return roleIds.stream()
                .findFirst()
                .orElseThrow(() ->
                        new AuthorizationPersistenceException(
                                "Role definition is unavailable in the requested Tenant scope"));
    }
}
