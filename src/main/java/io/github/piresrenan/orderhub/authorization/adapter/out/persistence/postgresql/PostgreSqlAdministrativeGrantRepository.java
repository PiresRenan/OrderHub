package io.github.piresrenan.orderhub.authorization.adapter.out.persistence.postgresql;

import java.util.Objects;
import java.util.UUID;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import io.github.piresrenan.orderhub.authorization.application.port.out.AdministrativeGrantMutationResult;
import io.github.piresrenan.orderhub.authorization.application.port.out.AdministrativeGrantRepository;
import io.github.piresrenan.orderhub.authorization.application.port.out.AuthorizationPersistenceException;
import io.github.piresrenan.orderhub.authorization.domain.model.AdministrativeGrant;

public final class PostgreSqlAdministrativeGrantRepository
        implements AdministrativeGrantRepository {

    private static final String GRANT_SQL = """
            INSERT INTO access_control.administrative_grants (
                grant_id,
                user_id,
                scope_type,
                scope_id,
                permission_code
            )
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT ON CONSTRAINT
                uq_authorization_administrative_grant_identity
            DO NOTHING
            """;

    private static final String REVOKE_SQL = """
            DELETE FROM access_control.administrative_grants
            WHERE user_id = ?
              AND scope_type = ?
              AND scope_id IS NOT DISTINCT FROM ?
              AND permission_code = ?
            """;

    private static final String EXISTS_SQL = """
            SELECT EXISTS (
                SELECT 1
                FROM access_control.administrative_grants
                WHERE user_id = ?
                  AND scope_type = ?
                  AND scope_id IS NOT DISTINCT FROM ?
                  AND permission_code = ?
            )
            """;

    private final JdbcTemplate jdbcTemplate;

    public PostgreSqlAdministrativeGrantRepository(
            JdbcTemplate jdbcTemplate) {

        this.jdbcTemplate =
                Objects.requireNonNull(
                        jdbcTemplate,
                        "jdbcTemplate");
    }

    @Override
    public AdministrativeGrantMutationResult grant(
            AdministrativeGrant grant) {

        Objects.requireNonNull(
                grant,
                "grant");

        try {
            var inserted =
                    jdbcTemplate.update(
                            GRANT_SQL,
                            UUID.randomUUID(),
                            grant.userId(),
                            grant.scope()
                                    .type()
                                    .name(),
                            grant.scope()
                                    .scopeId(),
                            grant.permission()
                                    .name());

            if (inserted == 1) {
                return AdministrativeGrantMutationResult.GRANTED;
            }

            return AdministrativeGrantMutationResult.ALREADY_GRANTED;

        } catch (DataAccessException exception) {

            throw new AuthorizationPersistenceException(
                    exception);
        }
    }

    @Override
    public AdministrativeGrantMutationResult revoke(
            AdministrativeGrant grant) {

        Objects.requireNonNull(
                grant,
                "grant");

        try {
            var deleted =
                    jdbcTemplate.update(
                            REVOKE_SQL,
                            grant.userId(),
                            grant.scope()
                                    .type()
                                    .name(),
                            grant.scope()
                                    .scopeId(),
                            grant.permission()
                                    .name());

            if (deleted == 1) {
                return AdministrativeGrantMutationResult.REVOKED;
            }

            return AdministrativeGrantMutationResult.ALREADY_ABSENT;

        } catch (DataAccessException exception) {

            throw new AuthorizationPersistenceException(
                    exception);
        }
    }

    @Override
    public boolean exists(
            AdministrativeGrant grant) {

        Objects.requireNonNull(
                grant,
                "grant");

        try {
            return Boolean.TRUE.equals(
                    jdbcTemplate.queryForObject(
                            EXISTS_SQL,
                            Boolean.class,
                            grant.userId(),
                            grant.scope()
                                    .type()
                                    .name(),
                            grant.scope()
                                    .scopeId(),
                            grant.permission()
                                    .name()));

        } catch (DataAccessException exception) {

            throw new AuthorizationPersistenceException(
                    exception);
        }
    }
}
