package io.github.piresrenan.orderhub.tenants.adapter.out.persistence.postgresql;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import io.github.piresrenan.orderhub.tenants.application.port.out.TenantBatchRepository;
import io.github.piresrenan.orderhub.tenants.application.port.out.TenantPersistenceException;
import io.github.piresrenan.orderhub.tenants.domain.model.Tenant;
import io.github.piresrenan.orderhub.tenants.domain.model.TenantStatus;

/**
 * Reads a bounded Tenant batch with one primary-key array lookup.
 */
public final class PostgreSqlTenantBatchRepository implements TenantBatchRepository {

    private static final String FIND_TENANTS_SQL = """
            SELECT
                id,
                name,
                status
            FROM tenants.tenants
            WHERE id = ANY (?)
            """;

    private final JdbcTemplate jdbcTemplate;

    /** Uses the shared JDBC boundary. */
    public PostgreSqlTenantBatchRepository(
            JdbcTemplate jdbcTemplate) {

        this.jdbcTemplate = jdbcTemplate;
    }

    /** Invalid persisted state fails reconstruction rather than being interpreted as ACTIVE. */
    @Override
    public List<Tenant> findAllById(
            Collection<UUID> tenantIds) {

        try {
            return jdbcTemplate.query(
                    FIND_TENANTS_SQL,
                    statement -> statement.setArray(
                            1,
                            statement.getConnection().createArrayOf("uuid", tenantIds.toArray())),
                    (resultSet, rowNumber) ->
                            Tenant.rehydrate(
                                    resultSet.getObject("id", UUID.class),
                                    resultSet.getString("name"),
                                    TenantStatus.valueOf(resultSet.getString("status"))));

        } catch (DataAccessException exception) {
            throw new TenantPersistenceException(exception);
        }
    }
}
