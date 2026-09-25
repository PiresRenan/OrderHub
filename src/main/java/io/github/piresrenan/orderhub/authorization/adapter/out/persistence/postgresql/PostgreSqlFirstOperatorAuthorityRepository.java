package io.github.piresrenan.orderhub.authorization.adapter.out.persistence.postgresql;

import java.util.Objects;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import io.github.piresrenan.orderhub.authorization.application.port.out.AuthorizationPersistenceException;
import io.github.piresrenan.orderhub.authorization.application.port.out.FirstOperatorAuthorityRepository;

/** Reads Platform grant presence only inside the caller-owned bootstrap transaction. */
public final class PostgreSqlFirstOperatorAuthorityRepository implements FirstOperatorAuthorityRepository {
    private final JdbcTemplate jdbc;

    /** Requires the shared JDBC access; construction performs no query. */
    public PostgreSqlFirstOperatorAuthorityRepository(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    /** Checks any Platform-scope grant, refusing to run outside an ambient transaction. */
    @Override
    public boolean platformAuthorityExists() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("First-operator authority requires the caller transaction");
        }
        try {
            return Boolean.TRUE.equals(jdbc.queryForObject("""
                    SELECT EXISTS (SELECT 1 FROM access_control.administrative_grants WHERE scope_type = 'PLATFORM')
                    """, Boolean.class));
        } catch (DataAccessException exception) {
            throw new AuthorizationPersistenceException(exception);
        }
    }
}
