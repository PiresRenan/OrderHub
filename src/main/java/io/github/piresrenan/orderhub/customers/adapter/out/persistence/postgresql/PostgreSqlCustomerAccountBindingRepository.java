package io.github.piresrenan.orderhub.customers.adapter.out.persistence.postgresql;

import java.util.UUID;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import io.github.piresrenan.orderhub.customers.application.port.out.CustomerAccountBindingPersistenceException;
import io.github.piresrenan.orderhub.customers.application.port.out.CustomerAccountBindingRepository;

/**
 * PostgreSQL adapter for exact Customer account relationship resolution.
 */
public final class PostgreSqlCustomerAccountBindingRepository
        implements CustomerAccountBindingRepository {

    private static final String EXISTS_EXACT_SQL = """
            SELECT EXISTS (
                SELECT 1
                FROM customers.customer_account_bindings
                WHERE tenant_id = ?
                  AND customer_id = ?
                  AND user_id = ?
            )
            """;

    private final JdbcTemplate jdbcTemplate;

    public PostgreSqlCustomerAccountBindingRepository(
            JdbcTemplate jdbcTemplate) {

        this.jdbcTemplate =
                jdbcTemplate;
    }

    @Override
    public boolean existsExact(
            UUID tenantId,
            UUID customerId,
            UUID userId) {

        try {
            return Boolean.TRUE.equals(
                    jdbcTemplate.queryForObject(
                            EXISTS_EXACT_SQL,
                            Boolean.class,
                            tenantId,
                            customerId,
                            userId));
        } catch (DataAccessException exception) {

            throw new CustomerAccountBindingPersistenceException(
                    exception);
        }
    }
}
