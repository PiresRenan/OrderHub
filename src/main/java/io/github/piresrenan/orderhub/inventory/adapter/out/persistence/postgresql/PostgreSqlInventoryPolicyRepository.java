package io.github.piresrenan.orderhub.inventory.adapter.out.persistence.postgresql;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import io.github.piresrenan.orderhub.inventory.application.port.out.InventoryPersistenceException;
import io.github.piresrenan.orderhub.inventory.application.port.out.InventoryPolicyRepository;
import io.github.piresrenan.orderhub.inventory.domain.model.InventoryPolicy;

/**
 * PostgreSQL adapter for tenant-scoped Inventory policy lookup.
 */
public final class PostgreSqlInventoryPolicyRepository
        implements InventoryPolicyRepository {

    private static final String FIND_POLICY_SQL = """
            SELECT
                policy
            FROM inventory.tenant_policies
            WHERE tenant_id = ?
            """;

    private final JdbcTemplate jdbcTemplate;

    public PostgreSqlInventoryPolicyRepository(
            JdbcTemplate jdbcTemplate) {

        this.jdbcTemplate =
                Objects.requireNonNull(
                        jdbcTemplate,
                        "jdbcTemplate");
    }

    @Override
    public Optional<InventoryPolicy> findByTenantId(
            UUID tenantId) {

        try {
            var policies =
                    jdbcTemplate.query(
                            FIND_POLICY_SQL,
                            (resultSet, rowNumber) ->
                                    InventoryPolicy.valueOf(
                                            resultSet.getString(
                                                    "policy")),
                            tenantId);

            return policies.stream()
                    .findFirst();
        } catch (DataAccessException exception) {
            throw new InventoryPersistenceException(
                    exception);
        }
    }
}