package io.github.piresrenan.orderhub.users.adapter.out.persistence.postgresql;

import java.util.List;
import java.util.UUID;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import io.github.piresrenan.orderhub.users.application.port.out.TenantMembershipPersistenceException;
import io.github.piresrenan.orderhub.users.application.port.out.TenantMembershipScanRepository;
import io.github.piresrenan.orderhub.users.domain.model.TenantMembershipStatus;

/**
 * Reads ordered windows of one User's ACTIVE memberships with explicit SQL.
 *
 * <p>
 * Separate statements for the first and later windows keep each plan a simple
 * range predicate over the User's memberships.
 * </p>
 */
public final class PostgreSqlTenantMembershipScanRepository
        implements TenantMembershipScanRepository {

    private static final String FIRST_WINDOW = """
            SELECT tenant_id
            FROM users.tenant_memberships
            WHERE user_id = ?
              AND status = ?
            ORDER BY tenant_id
            LIMIT ?
            """;

    private static final String NEXT_WINDOW = """
            SELECT tenant_id
            FROM users.tenant_memberships
            WHERE user_id = ?
              AND status = ?
              AND tenant_id > ?
            ORDER BY tenant_id
            LIMIT ?
            """;

    private final JdbcTemplate jdbcTemplate;

    /** Uses the shared JDBC boundary; no state is held between windows. */
    public PostgreSqlTenantMembershipScanRepository(
            JdbcTemplate jdbcTemplate) {

        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public List<UUID> findOperationallyActiveTenantIds(
            UUID userId,
            UUID afterTenantId,
            int maxRows) {

        var active = TenantMembershipStatus.ACTIVE.name();

        try {
            if (afterTenantId == null) {
                return jdbcTemplate.query(
                        FIRST_WINDOW,
                        (resultSet, rowNumber) -> resultSet.getObject("tenant_id", UUID.class),
                        userId, active, maxRows);
            }

            return jdbcTemplate.query(
                    NEXT_WINDOW,
                    (resultSet, rowNumber) -> resultSet.getObject("tenant_id", UUID.class),
                    userId, active, afterTenantId, maxRows);

        } catch (DataAccessException exception) {
            throw new TenantMembershipPersistenceException(exception);
        }
    }
}
