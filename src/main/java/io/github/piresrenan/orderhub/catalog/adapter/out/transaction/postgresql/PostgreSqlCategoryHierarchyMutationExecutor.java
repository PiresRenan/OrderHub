package io.github.piresrenan.orderhub.catalog.adapter.out.transaction.postgresql;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionOperations;

import io.github.piresrenan.orderhub.catalog.application.port.out.CatalogPersistenceException;
import io.github.piresrenan.orderhub.catalog.application.port.out.CategoryHierarchyMutationExecutor;
import io.github.piresrenan.orderhub.catalog.domain.model.Category;

/**
 * PostgreSQL transaction boundary for tenant-scoped Category hierarchy writes.
 */
public final class PostgreSqlCategoryHierarchyMutationExecutor
        implements CategoryHierarchyMutationExecutor {

    private static final String ENSURE_GUARD_SQL = """
            INSERT INTO catalog.category_hierarchy_guards (
                tenant_id
            )
            VALUES (?)
            ON CONFLICT (tenant_id)
            DO NOTHING
            """;

    private static final String LOCK_GUARD_SQL = """
            SELECT tenant_id
            FROM catalog.category_hierarchy_guards
            WHERE tenant_id = ?
            FOR UPDATE
            """;

    private final JdbcTemplate jdbcTemplate;
    private final TransactionOperations transactionOperations;

    public PostgreSqlCategoryHierarchyMutationExecutor(
            JdbcTemplate jdbcTemplate,
            TransactionOperations transactionOperations) {

        this.jdbcTemplate =
                Objects.requireNonNull(
                        jdbcTemplate,
                        "jdbcTemplate");

        this.transactionOperations =
                Objects.requireNonNull(
                        transactionOperations,
                        "transactionOperations");
    }

    @Override
    public Category execute(
            UUID tenantId,
            Supplier<Category> action) {

        Objects.requireNonNull(
                tenantId,
                "tenantId");

        Objects.requireNonNull(
                action,
                "action");

        try {
            return transactionOperations.execute(
                    transactionStatus -> {

                        jdbcTemplate.update(
                                ENSURE_GUARD_SQL,
                                tenantId);

                        var lockedTenantId =
                                jdbcTemplate.queryForObject(
                                        LOCK_GUARD_SQL,
                                        UUID.class,
                                        tenantId);

                        if (!tenantId.equals(
                                lockedTenantId)) {

                            throw new CatalogPersistenceException(
                                    new IllegalStateException(
                                            "Category hierarchy guard identity mismatch"));
                        }

                        return action.get();
                    });

        } catch (
                DataAccessException
                | TransactionException exception) {

            throw new CatalogPersistenceException(
                    exception);
        }
    }
}
