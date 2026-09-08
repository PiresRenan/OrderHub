package io.github.piresrenan.orderhub.users.adapter.out.persistence.postgresql;

import java.util.Optional;
import java.util.UUID;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import io.github.piresrenan.orderhub.users.application.port.out.TenantMembershipAlreadyExistsException;
import io.github.piresrenan.orderhub.users.application.port.out.TenantMembershipPersistenceException;
import io.github.piresrenan.orderhub.users.application.port.out.TenantMembershipRepository;
import io.github.piresrenan.orderhub.users.domain.model.TenantMembership;
import io.github.piresrenan.orderhub.users.domain.model.TenantMembershipStatus;

/**
 * PostgreSQL implementation of the TenantMembership persistence boundary.
 *
 * <p>
 * Pair uniqueness is resolved atomically by PostgreSQL rather than through a
 * check-then-insert sequence.
 * </p>
 */
public final class PostgreSqlTenantMembershipRepository
        implements TenantMembershipRepository {

    private static final String INSERT_MEMBERSHIP = """
            INSERT INTO users.tenant_memberships (
                user_id,
                tenant_id,
                status
            )
            VALUES (?, ?, ?)
            ON CONFLICT (tenant_id, user_id) DO NOTHING
            """;

    private static final String FIND_MEMBERSHIP = """
            SELECT
                user_id,
                tenant_id,
                status
            FROM users.tenant_memberships
            WHERE user_id = ?
              AND tenant_id = ?
            """;

    private final JdbcTemplate jdbcTemplate;

    /**
     * Creates the PostgreSQL TenantMembership repository.
     *
     * @param jdbcTemplate JDBC boundary used to execute explicit SQL
     */
    public PostgreSqlTenantMembershipRepository(
            JdbcTemplate jdbcTemplate) {

        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Persists one User/Tenant membership and its explicit lifecycle state
     * atomically.
     *
     * <p>
     * PostgreSQL resolves concurrent attempts against the durable unique
     * constraint. A zero update count therefore represents the exact
     * User/Tenant pair already being present, and the stored lifecycle of that
     * existing pair is never overwritten here.
     * </p>
     *
     * @param membership valid membership requested for persistence
     * @return the same membership after successful insertion
     * @throws TenantMembershipAlreadyExistsException when the pair already exists
     * @throws TenantMembershipPersistenceException when persistence otherwise fails
     */
    @Override
    public TenantMembership save(
            TenantMembership membership) {

        try {
            var insertedRows = jdbcTemplate.update(
                    INSERT_MEMBERSHIP,
                    membership.userId(),
                    membership.tenantId(),
                    membership.status().name());

            if (insertedRows == 0) {
                throw new TenantMembershipAlreadyExistsException();
            }

            return membership;
        } catch (TenantMembershipAlreadyExistsException exception) {
            throw exception;
        } catch (DataAccessException exception) {
            throw new TenantMembershipPersistenceException(
                    exception);
        }
    }

    /**
     * Retrieves one exact User/Tenant membership pair and reconstructs its
     * domain representation.
     *
     * <p>
     * The persisted lifecycle value is mapped strictly, so an unrecognized
     * status is reported as a persistence failure instead of being silently
     * interpreted as operational.
     * </p>
     *
     * @param userId internal User identifier
     * @param tenantId Tenant identifier
     * @return matching membership when present, otherwise empty
     * @throws TenantMembershipPersistenceException when PostgreSQL access fails
     *                                              or persisted state is invalid
     */
    @Override
    public Optional<TenantMembership> find(
            UUID userId,
            UUID tenantId) {

        try {
            return jdbcTemplate.query(
                            FIND_MEMBERSHIP,
                            (resultSet, rowNumber) ->
                                    TenantMembership.rehydrate(
                                            resultSet.getObject(
                                                    "user_id",
                                                    UUID.class),
                                            resultSet.getObject(
                                                    "tenant_id",
                                                    UUID.class),
                                            TenantMembershipStatus.valueOf(
                                                    resultSet.getString(
                                                            "status"))),
                            userId,
                            tenantId)
                    .stream()
                    .findFirst();
        } catch (DataAccessException | IllegalArgumentException exception) {
            throw new TenantMembershipPersistenceException(
                    exception);
        }
    }
}
