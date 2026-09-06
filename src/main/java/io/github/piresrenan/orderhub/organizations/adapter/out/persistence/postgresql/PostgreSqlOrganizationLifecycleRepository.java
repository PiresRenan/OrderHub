package io.github.piresrenan.orderhub.organizations.adapter.out.persistence.postgresql;

import java.util.UUID;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import io.github.piresrenan.orderhub.organizations.application.port.out.OrganizationLifecycleMutationResult;
import io.github.piresrenan.orderhub.organizations.application.port.out.OrganizationLifecycleRepository;
import io.github.piresrenan.orderhub.organizations.application.port.out.OrganizationPersistenceException;
import io.github.piresrenan.orderhub.organizations.domain.model.OrganizationStatus;

public final class PostgreSqlOrganizationLifecycleRepository
        implements OrganizationLifecycleRepository {

    private static final String LOCK_ORGANIZATION_SQL = """
            SELECT status
            FROM organizations.organizations
            WHERE id = ?
            FOR NO KEY UPDATE
            """;

    private static final String UPDATE_STATUS_SQL = """
            UPDATE organizations.organizations
            SET status = ?
            WHERE id = ?
            """;

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;

    public PostgreSqlOrganizationLifecycleRepository(
            JdbcTemplate jdbcTemplate,
            PlatformTransactionManager transactionManager) {

        if (jdbcTemplate == null) {
            throw new IllegalArgumentException(
                    "JDBC template is required");
        }

        if (transactionManager == null) {
            throw new IllegalArgumentException(
                    "Transaction manager is required");
        }

        this.jdbcTemplate =
                jdbcTemplate;

        this.transactionTemplate =
                new TransactionTemplate(
                        transactionManager);
    }

    @Override
    public OrganizationLifecycleMutationResult setStatus(
            UUID organizationId,
            OrganizationStatus desiredStatus) {

        if (organizationId == null) {
            throw new IllegalArgumentException(
                    "Organization lifecycle id is required");
        }

        if (desiredStatus == null) {
            throw new IllegalArgumentException(
                    "Organization desired lifecycle status is required");
        }

        try {
            var result =
                    transactionTemplate.execute(
                            transactionStatus ->
                                    setStatusInTransaction(
                                            organizationId,
                                            desiredStatus));

            if (result == null) {
                throw new OrganizationPersistenceException(
                        new IllegalStateException(
                                "Organization lifecycle transaction returned no result"));
            }

            return result;

        } catch (OrganizationPersistenceException exception) {

            throw exception;

        } catch (DataAccessException exception) {

            throw new OrganizationPersistenceException(
                    exception);
        }
    }

    private OrganizationLifecycleMutationResult setStatusInTransaction(
            UUID organizationId,
            OrganizationStatus desiredStatus) {

        var statuses =
                jdbcTemplate.query(
                        LOCK_ORGANIZATION_SQL,
                        (resultSet, rowNumber) ->
                                OrganizationStatus.valueOf(
                                        resultSet.getString(
                                                "status")),
                        organizationId);

        if (statuses.isEmpty()) {
            return OrganizationLifecycleMutationResult.NOT_FOUND;
        }

        var currentStatus =
                statuses.getFirst();

        if (currentStatus == desiredStatus) {
            return OrganizationLifecycleMutationResult.ALREADY_DESIRED;
        }

        var updated =
                jdbcTemplate.update(
                        UPDATE_STATUS_SQL,
                        desiredStatus.name(),
                        organizationId);

        if (updated != 1) {
            throw new OrganizationPersistenceException(
                    new IllegalStateException(
                            "Unexpected Organization lifecycle update cardinality"));
        }

        return OrganizationLifecycleMutationResult.UPDATED;
    }
}
