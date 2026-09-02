package io.github.piresrenan.orderhub.authorization.adapter.out.persistence.postgresql;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import io.github.piresrenan.orderhub.authorization.application.port.out.AuthorizationPersistenceException;
import io.github.piresrenan.orderhub.authorization.application.port.out.UserPermissionOverrideRepository;
import io.github.piresrenan.orderhub.authorization.domain.model.PermissionCode;
import io.github.piresrenan.orderhub.authorization.domain.model.PermissionEffect;
import io.github.piresrenan.orderhub.authorization.domain.model.PermissionOverride;
import io.github.piresrenan.orderhub.authorization.domain.model.TenantAuthorizationScope;
import io.github.piresrenan.orderhub.authorization.domain.model.UserPermissionOverride;

/**
 * PostgreSQL adapter for Tenant-scoped direct permission overrides.
 */
public final class PostgreSqlUserPermissionOverrideRepository
        implements UserPermissionOverrideRepository {

    private static final String FIND_OVERRIDES_SQL = """
            SELECT
                user_id,
                tenant_id,
                permission_code,
                effect
            FROM access_control.user_permission_overrides
            WHERE user_id = ?
              AND tenant_id = ?
            ORDER BY permission_code
            """;

    private final JdbcTemplate jdbcTemplate;

    public PostgreSqlUserPermissionOverrideRepository(
            JdbcTemplate jdbcTemplate) {

        this.jdbcTemplate =
                Objects.requireNonNull(
                        jdbcTemplate,
                        "jdbcTemplate");
    }

    @Override
    public List<UserPermissionOverride> findByUserIdAndScope(
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
                            FIND_OVERRIDES_SQL,
                            (resultSet, rowNumber) ->
                                    new UserPermissionOverride(
                                            resultSet.getObject(
                                                    "user_id",
                                                    UUID.class),
                                            new TenantAuthorizationScope(
                                                    resultSet.getObject(
                                                            "tenant_id",
                                                            UUID.class)),
                                            new PermissionOverride(
                                                    PermissionCode.valueOf(
                                                            resultSet.getString(
                                                                    "permission_code")),
                                                    PermissionEffect.valueOf(
                                                            resultSet.getString(
                                                                    "effect")))),
                            userId,
                            scope.tenantId()));

        } catch (
                DataAccessException
                | IllegalArgumentException exception) {

            throw new AuthorizationPersistenceException(
                    exception);
        }
    }
}
