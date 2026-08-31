package io.github.piresrenan.orderhub.users.adapter.out.persistence.postgresql;

import java.util.Optional;
import java.util.UUID;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import io.github.piresrenan.orderhub.users.application.port.out.ExternalIdentityBindingAlreadyExistsException;
import io.github.piresrenan.orderhub.users.application.port.out.ExternalIdentityBindingPersistenceException;
import io.github.piresrenan.orderhub.users.application.port.out.ExternalIdentityBindingRepository;
import io.github.piresrenan.orderhub.users.domain.model.ExternalIdentityBinding;

/**
 * PostgreSQL implementation of the ExternalIdentityBinding persistence
 * boundary.
 *
 * <p>
 * External identity values are persisted and queried exactly as supplied.
 * This adapter performs no trimming, case conversion or provider-specific
 * normalization.
 * </p>
 *
 * <p>
 * Pair uniqueness is resolved atomically by PostgreSQL rather than through a
 * check-then-insert sequence.
 * </p>
 */
public final class PostgreSqlExternalIdentityBindingRepository
                implements ExternalIdentityBindingRepository {

        private static final String INSERT_BINDING = """
                        INSERT INTO users.external_identity_bindings (
                            issuer,
                            subject,
                            user_id
                        )
                        VALUES (?, ?, ?)
                        ON CONFLICT (issuer, subject) DO NOTHING
                        """;

        private static final String FIND_BINDING = """
                        SELECT
                            issuer,
                            subject,
                            user_id
                        FROM users.external_identity_bindings
                        WHERE issuer = ?
                          AND subject = ?
                        """;

        private final JdbcTemplate jdbcTemplate;

        /**
         * Creates the PostgreSQL external identity binding repository.
         *
         * @param jdbcTemplate JDBC boundary used to execute explicit SQL
         */
        public PostgreSqlExternalIdentityBindingRepository(
                        JdbcTemplate jdbcTemplate) {

                this.jdbcTemplate = jdbcTemplate;
        }

        /**
         * Persists one validated external identity association atomically.
         *
         * <p>
         * PostgreSQL resolves concurrent attempts against the durable exact-pair
         * unique constraint. A zero update count therefore means that the exact
         * issuer/subject pair already exists.
         * </p>
         *
         * <p>
         * Other PostgreSQL failures are translated into a stable
         * infrastructure-independent persistence exception.
         * </p>
         *
         * @param binding valid external identity association
         * @return the same binding after successful persistence
         * @throws ExternalIdentityBindingAlreadyExistsException when the exact
         *                                                       issuer/subject pair
         *                                                       already exists
         * @throws ExternalIdentityBindingPersistenceException   when persistence
         *                                                       otherwise fails
         */
        @Override
        public ExternalIdentityBinding save(
                        ExternalIdentityBinding binding) {

                try {
                        var insertedRows = jdbcTemplate.update(
                                        INSERT_BINDING,
                                        binding.issuer(),
                                        binding.subject(),
                                        binding.userId());

                        if (insertedRows == 0) {
                                throw new ExternalIdentityBindingAlreadyExistsException();
                        }

                        return binding;
                } catch (ExternalIdentityBindingAlreadyExistsException exception) {
                        throw exception;
                } catch (DataAccessException exception) {
                        throw new ExternalIdentityBindingPersistenceException(
                                        exception);
                }
        }

        /**
         * Finds one external identity association by its exact issuer/subject pair
         * and reconstructs the corresponding domain representation.
         *
         * <p>
         * Absence is represented normally as {@link Optional#empty()}. No
         * normalization is applied before querying PostgreSQL.
         * </p>
         *
         * @param issuer  exact external identity issuer
         * @param subject exact external identity subject
         * @return matching binding when present, otherwise empty
         * @throws ExternalIdentityBindingPersistenceException when PostgreSQL access
         *                                                     fails
         */
        @Override
        public Optional<ExternalIdentityBinding> find(
                        String issuer,
                        String subject) {

                try {
                        return jdbcTemplate.query(
                                        FIND_BINDING,
                                        (resultSet, rowNumber) -> ExternalIdentityBinding.rehydrate(
                                                        resultSet.getString("issuer"),
                                                        resultSet.getString("subject"),
                                                        resultSet.getObject(
                                                                        "user_id",
                                                                        UUID.class)),
                                        issuer,
                                        subject)
                                        .stream()
                                        .findFirst();
                } catch (DataAccessException exception) {
                        throw new ExternalIdentityBindingPersistenceException(
                                        exception);
                }
        }
}
