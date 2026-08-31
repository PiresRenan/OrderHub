package io.github.piresrenan.orderhub.users.adapter.out.persistence.postgresql;

import java.util.Optional;
import java.util.UUID;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import io.github.piresrenan.orderhub.users.application.port.out.UserPersistenceException;
import io.github.piresrenan.orderhub.users.application.port.out.UserRepository;
import io.github.piresrenan.orderhub.users.domain.model.User;

/**
 * PostgreSQL implementation of the User persistence boundary.
 *
 * <p>
 * SQL remains explicit inside the infrastructure adapter and persisted rows are
 * reconstructed through the User domain contract.
 * </p>
 */
public final class PostgreSqlUserRepository
        implements UserRepository {

    private static final String INSERT_USER = """
            INSERT INTO users.users (
                id
            )
            VALUES (?)
            """;

    private static final String FIND_USER_BY_ID = """
            SELECT id
            FROM users.users
            WHERE id = ?
            """;

    private final JdbcTemplate jdbcTemplate;

    /**
     * Creates the PostgreSQL User repository.
     *
     * @param jdbcTemplate JDBC boundary used to execute explicit SQL
     */
    public PostgreSqlUserRepository(
            JdbcTemplate jdbcTemplate) {

        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Persists one complete User aggregate.
     *
     * @param user valid User aggregate
     * @return the same aggregate after successful persistence
     * @throws UserPersistenceException when PostgreSQL persistence fails
     */
    @Override
    public User save(User user) {
        try {
            jdbcTemplate.update(
                    INSERT_USER,
                    user.id());

            return user;
        } catch (DataAccessException exception) {
            throw new UserPersistenceException(
                    exception);
        }
    }

    /**
     * Retrieves and reconstructs one User by its internal identity.
     *
     * <p>
     * An absent row is represented by {@link Optional#empty()} and is not treated
     * as an infrastructure failure.
     * </p>
     *
     * @param userId internal User identifier
     * @return reconstructed User when present, otherwise empty
     * @throws UserPersistenceException when PostgreSQL access fails
     */
    @Override
    public Optional<User> findById(UUID userId) {
        try {
            return jdbcTemplate.query(
                            FIND_USER_BY_ID,
                            (resultSet, rowNumber) ->
                                    User.rehydrate(
                                            resultSet.getObject(
                                                    "id",
                                                    UUID.class)),
                            userId)
                    .stream()
                    .findFirst();
        } catch (DataAccessException exception) {
            throw new UserPersistenceException(
                    exception);
        }
    }
}
