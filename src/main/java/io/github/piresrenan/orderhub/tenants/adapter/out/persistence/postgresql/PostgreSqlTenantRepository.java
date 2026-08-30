package io.github.piresrenan.orderhub.tenants.adapter.out.persistence.postgresql;

import java.util.Optional;
import java.util.UUID;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import io.github.piresrenan.orderhub.tenants.application.port.out.TenantPersistenceException;
import io.github.piresrenan.orderhub.tenants.application.port.out.TenantRepository;
import io.github.piresrenan.orderhub.tenants.domain.model.Tenant;

/**
 * Persists and reconstructs Tenant aggregates through explicit PostgreSQL SQL.
 *
 * <p>
 * Relational mapping remains owned by this adapter while the Tenant domain and
 * application layers remain independent of Spring JDBC and PostgreSQL.
 * </p>
 *
 * <p>
 * Tenant currently maps to a single relational row. A single PostgreSQL INSERT
 * is already atomic, so no explicit transaction abstraction is introduced until
 * a concrete use case requires multiple writes to form one aggregate operation.
 * </p>
 */
public final class PostgreSqlTenantRepository implements TenantRepository {

    private static final String INSERT_TENANT_SQL = """
            INSERT INTO tenants.tenants (
                id,
                name
            )
            VALUES (?, ?)
            """;

    private static final String FIND_TENANT_SQL = """
            SELECT
                id,
                name
            FROM tenants.tenants
            WHERE id = ?
            """;

    private final JdbcTemplate jdbcTemplate;

    /**
     * Creates the PostgreSQL Tenant repository using externally configured JDBC
     * infrastructure.
     *
     * <p>
     * The adapter deliberately has no Spring component annotation. Runtime
     * composition remains an explicit configuration responsibility.
     * </p>
     *
     * @param jdbcTemplate JDBC operations configured for the OrderHub database
     */
    public PostgreSqlTenantRepository(
            JdbcTemplate jdbcTemplate) {

        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Persists one complete Tenant aggregate.
     *
     * @param tenant valid Tenant aggregate to persist
     * @return the same aggregate after successful persistence
     * @throws TenantPersistenceException when PostgreSQL persistence cannot be
     *                                    completed
     */
    @Override
    public Tenant save(Tenant tenant) {
        try {
            jdbcTemplate.update(
                    INSERT_TENANT_SQL,
                    tenant.id(),
                    tenant.name());

            return tenant;
        } catch (DataAccessException exception) {
            throw new TenantPersistenceException(
                    exception);
        }
    }

    /**
     * Loads one Tenant by aggregate identity and reconstructs it through the
     * domain rehydration contract.
     *
     * <p>
     * An absent row is a normal repository result and therefore returns an empty
     * Optional rather than a persistence failure.
     * </p>
     *
     * @param tenantId Tenant aggregate identifier
     * @return reconstructed Tenant when present, otherwise empty
     * @throws TenantPersistenceException when the lookup cannot be completed
     */
    @Override
    public Optional<Tenant> findById(UUID tenantId) {
        try {
            var tenants = jdbcTemplate.query(
                    FIND_TENANT_SQL,
                    (resultSet, rowNumber) -> Tenant.rehydrate(
                            resultSet.getObject(
                                    "id",
                                    UUID.class),
                            resultSet.getString(
                                    "name")),
                    tenantId);

            return tenants.stream()
                    .findFirst();
        } catch (DataAccessException exception) {
            throw new TenantPersistenceException(
                    exception);
        }
    }
}
