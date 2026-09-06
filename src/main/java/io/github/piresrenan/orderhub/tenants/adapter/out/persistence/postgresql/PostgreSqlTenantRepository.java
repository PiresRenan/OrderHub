package io.github.piresrenan.orderhub.tenants.adapter.out.persistence.postgresql;

import java.util.Optional;
import java.util.UUID;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import io.github.piresrenan.orderhub.tenants.application.port.out.TenantPersistenceException;
import io.github.piresrenan.orderhub.tenants.application.port.out.TenantRepository;
import io.github.piresrenan.orderhub.tenants.domain.model.Tenant;
import io.github.piresrenan.orderhub.tenants.domain.model.TenantStatus;

/**
 * Persists and reconstructs Tenant aggregates through explicit PostgreSQL SQL.
 */
public final class PostgreSqlTenantRepository implements TenantRepository {

    private static final String INSERT_TENANT_SQL = """
            INSERT INTO tenants.tenants (
                id,
                name,
                status
            )
            VALUES (?, ?, ?)
            """;

    private static final String FIND_TENANT_SQL = """
            SELECT
                id,
                name,
                status
            FROM tenants.tenants
            WHERE id = ?
            """;

    private final JdbcTemplate jdbcTemplate;

    public PostgreSqlTenantRepository(
            JdbcTemplate jdbcTemplate) {

        this.jdbcTemplate =
                jdbcTemplate;
    }

    @Override
    public Tenant save(
            Tenant tenant) {

        try {
            jdbcTemplate.update(
                    INSERT_TENANT_SQL,
                    tenant.id(),
                    tenant.name(),
                    tenant.status().name());

            return tenant;

        } catch (DataAccessException exception) {

            throw new TenantPersistenceException(
                    exception);
        }
    }

    @Override
    public Optional<Tenant> findById(
            UUID tenantId) {

        try {
            var tenants =
                    jdbcTemplate.query(
                            FIND_TENANT_SQL,
                            (resultSet, rowNumber) ->
                                    Tenant.rehydrate(
                                            resultSet.getObject(
                                                    "id",
                                                    UUID.class),
                                            resultSet.getString(
                                                    "name"),
                                            TenantStatus.valueOf(
                                                    resultSet.getString(
                                                            "status"))),
                            tenantId);

            return tenants.stream()
                    .findFirst();

        } catch (DataAccessException exception) {

            throw new TenantPersistenceException(
                    exception);
        }
    }
}
