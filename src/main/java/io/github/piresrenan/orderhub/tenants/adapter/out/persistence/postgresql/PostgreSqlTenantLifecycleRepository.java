package io.github.piresrenan.orderhub.tenants.adapter.out.persistence.postgresql;

import java.util.Objects;
import java.util.UUID;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import io.github.piresrenan.orderhub.tenants.application.port.out.TenantLifecycleMutationResult;
import io.github.piresrenan.orderhub.tenants.application.port.out.TenantLifecycleRepository;
import io.github.piresrenan.orderhub.tenants.application.port.out.TenantPersistenceException;
import io.github.piresrenan.orderhub.tenants.domain.model.TenantStatus;

public final class PostgreSqlTenantLifecycleRepository
        implements TenantLifecycleRepository {

    private static final String LOCK_SQL = """
            SELECT status
            FROM tenants.tenants
            WHERE id = ?
            FOR NO KEY UPDATE
            """;

    private static final String UPDATE_SQL = """
            UPDATE tenants.tenants
            SET status = ?
            WHERE id = ?
            """;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public PostgreSqlTenantLifecycleRepository(
            JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.transactions = new TransactionTemplate(
                Objects.requireNonNull(transactionManager, "transactionManager"));
    }

    @Override
    public TenantLifecycleMutationResult setStatus(
            UUID tenantId,
            TenantStatus desiredStatus) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(desiredStatus, "desiredStatus");
        try {
            var result = transactions.execute(status ->
                    setStatusInTransaction(tenantId, desiredStatus));
            if (result == null) {
                throw new TenantPersistenceException(new IllegalStateException(
                        "Tenant lifecycle transaction returned no result"));
            }
            return result;
        } catch (TenantPersistenceException exception) {
            throw exception;
        } catch (DataAccessException exception) {
            throw new TenantPersistenceException(exception);
        }
    }

    private TenantLifecycleMutationResult setStatusInTransaction(
            UUID tenantId,
            TenantStatus desiredStatus) {
        var statuses = jdbc.query(
                LOCK_SQL,
                (resultSet, rowNumber) -> TenantStatus.valueOf(
                        resultSet.getString("status")),
                tenantId);
        if (statuses.isEmpty()) {
            return TenantLifecycleMutationResult.NOT_FOUND;
        }
        if (statuses.getFirst() == desiredStatus) {
            return TenantLifecycleMutationResult.ALREADY_DESIRED;
        }
        if (jdbc.update(UPDATE_SQL, desiredStatus.name(), tenantId) != 1) {
            throw new TenantPersistenceException(new IllegalStateException(
                    "Unexpected Tenant lifecycle update cardinality"));
        }
        return TenantLifecycleMutationResult.UPDATED;
    }
}
