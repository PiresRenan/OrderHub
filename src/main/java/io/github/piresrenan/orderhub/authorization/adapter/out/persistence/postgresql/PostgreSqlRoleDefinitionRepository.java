package io.github.piresrenan.orderhub.authorization.adapter.out.persistence.postgresql;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import io.github.piresrenan.orderhub.authorization.application.port.out.AuthorizationPersistenceException;
import io.github.piresrenan.orderhub.authorization.application.port.out.RoleDefinitionRepository;
import io.github.piresrenan.orderhub.authorization.domain.model.AuthorityBand;
import io.github.piresrenan.orderhub.authorization.domain.model.AuthorizationPersona;
import io.github.piresrenan.orderhub.authorization.domain.model.PermissionCode;
import io.github.piresrenan.orderhub.authorization.domain.model.PermissionEnvelope;
import io.github.piresrenan.orderhub.authorization.domain.model.RoleDefinition;
import io.github.piresrenan.orderhub.authorization.domain.model.RoleMutability;
import io.github.piresrenan.orderhub.authorization.domain.model.TenantAuthorizationScope;

/**
 * PostgreSQL adapter for durable scoped RoleDefinition reads.
 */
public final class PostgreSqlRoleDefinitionRepository
        implements RoleDefinitionRepository {

    private static final String FIND_ROLE_SQL = """
            SELECT
                role_id,
                code,
                persona,
                authority_band,
                mutability
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

    private static final String FIND_PERMISSIONS_SQL = """
            SELECT permission_code
            FROM access_control.role_permissions
            WHERE role_id = ?
            ORDER BY permission_code
            """;

    private final JdbcTemplate jdbcTemplate;

    public PostgreSqlRoleDefinitionRepository(
            JdbcTemplate jdbcTemplate) {

        this.jdbcTemplate =
                Objects.requireNonNull(
                        jdbcTemplate,
                        "jdbcTemplate");
    }

    @Override
    public Optional<RoleDefinition> findByCodeAndScope(
            String roleCode,
            TenantAuthorizationScope scope) {

        Objects.requireNonNull(
                roleCode,
                "roleCode");

        Objects.requireNonNull(
                scope,
                "scope");

        try {
            var tenantId =
                    scope.tenantId();

            var rows =
                    jdbcTemplate.query(
                            FIND_ROLE_SQL,
                            (resultSet, rowNumber) ->
                                    new PersistedRole(
                                            resultSet.getObject(
                                                    "role_id",
                                                    UUID.class),
                                            resultSet.getString(
                                                    "code"),
                                            AuthorizationPersona.valueOf(
                                                    resultSet.getString(
                                                            "persona")),
                                            AuthorityBand.valueOf(
                                                    resultSet.getString(
                                                            "authority_band")),
                                            RoleMutability.valueOf(
                                                    resultSet.getString(
                                                            "mutability"))),
                            roleCode,
                            tenantId,
                            tenantId);

            if (rows.isEmpty()) {
                return Optional.empty();
            }

            var row =
                    rows.getFirst();

            var permissions =
                    loadPermissions(
                            row.roleId());

            return Optional.of(
                    new RoleDefinition(
                            row.code(),
                            row.persona(),
                            row.authorityBand(),
                            row.mutability(),
                            permissions,
                            PermissionEnvelope.of(
                                    permissions)));

        } catch (
                DataAccessException
                | IllegalArgumentException exception) {

            throw new AuthorizationPersistenceException(
                    exception);
        }
    }

    private Set<PermissionCode> loadPermissions(
            UUID roleId) {

        return jdbcTemplate.queryForList(
                        FIND_PERMISSIONS_SQL,
                        String.class,
                        roleId)
                .stream()
                .map(PermissionCode::valueOf)
                .collect(
                        Collectors.toUnmodifiableSet());
    }

    private record PersistedRole(
            UUID roleId,
            String code,
            AuthorizationPersona persona,
            AuthorityBand authorityBand,
            RoleMutability mutability) {
    }
}
