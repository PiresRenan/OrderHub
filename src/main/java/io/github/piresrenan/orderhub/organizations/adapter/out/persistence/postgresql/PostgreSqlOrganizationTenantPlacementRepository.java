package io.github.piresrenan.orderhub.organizations.adapter.out.persistence.postgresql;

import java.util.Optional;
import java.util.UUID;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import io.github.piresrenan.orderhub.organizations.application.port.out.OrganizationPersistenceException;
import io.github.piresrenan.orderhub.organizations.application.port.out.OrganizationTenantPlacementRepository;
import io.github.piresrenan.orderhub.organizations.application.port.out.OrganizationTenantPlacementResult;
import io.github.piresrenan.orderhub.organizations.domain.model.OrganizationStatus;
import io.github.piresrenan.orderhub.organizations.domain.model.OrganizationTenantPlacement;

public final class PostgreSqlOrganizationTenantPlacementRepository
        implements OrganizationTenantPlacementRepository {

    private static final String LOCK_DESTINATION_SQL = """
            SELECT status
            FROM organizations.organizations
            WHERE id = ?
            FOR SHARE
            """;

    private static final String ATTACH_SQL = """
            INSERT INTO organizations.tenant_placements (
                tenant_id,
                organization_id
            )
            VALUES (?, ?)
            ON CONFLICT (tenant_id)
            DO NOTHING
            """;

    private static final String MOVE_SQL = """
            UPDATE organizations.tenant_placements
            SET organization_id = ?
            WHERE tenant_id = ?
              AND organization_id = ?
            """;

    private static final String DETACH_SQL = """
            DELETE FROM organizations.tenant_placements
            WHERE tenant_id = ?
              AND organization_id = ?
            """;

    private static final String FIND_BY_TENANT_SQL = """
            SELECT
                tenant_id,
                organization_id
            FROM organizations.tenant_placements
            WHERE tenant_id = ?
            """;

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;

    public PostgreSqlOrganizationTenantPlacementRepository(
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
    public OrganizationTenantPlacementResult attach(
            UUID tenantId,
            UUID destinationOrganizationId) {

        validateRequiredId(
                tenantId,
                "Placement tenant id is required");

        validateRequiredId(
                destinationOrganizationId,
                "Placement destination organization id is required");

        try {
            return transactionTemplate.execute(
                    transactionStatus ->
                            attachInTransaction(
                                    tenantId,
                                    destinationOrganizationId));

        } catch (DataAccessException exception) {

            throw new OrganizationPersistenceException(
                    exception);
        }
    }

    @Override
    public OrganizationTenantPlacementResult move(
            UUID tenantId,
            UUID expectedSourceOrganizationId,
            UUID destinationOrganizationId) {

        validateRequiredId(
                tenantId,
                "Placement tenant id is required");

        validateRequiredId(
                expectedSourceOrganizationId,
                "Placement expected source organization id is required");

        validateRequiredId(
                destinationOrganizationId,
                "Placement destination organization id is required");

        try {
            return transactionTemplate.execute(
                    transactionStatus ->
                            moveInTransaction(
                                    tenantId,
                                    expectedSourceOrganizationId,
                                    destinationOrganizationId));

        } catch (DataAccessException exception) {

            throw new OrganizationPersistenceException(
                    exception);
        }
    }

    @Override
    public OrganizationTenantPlacementResult detach(
            UUID tenantId,
            UUID expectedOrganizationId) {

        validateRequiredId(
                tenantId,
                "Placement tenant id is required");

        validateRequiredId(
                expectedOrganizationId,
                "Placement expected organization id is required");

        try {
            return transactionTemplate.execute(
                    transactionStatus ->
                            detachInTransaction(
                                    tenantId,
                                    expectedOrganizationId));

        } catch (DataAccessException exception) {

            throw new OrganizationPersistenceException(
                    exception);
        }
    }

    @Override
    public Optional<OrganizationTenantPlacement> findByTenantId(
            UUID tenantId) {

        validateRequiredId(
                tenantId,
                "Placement tenant id is required");

        try {
            return findCurrentPlacement(
                    tenantId);

        } catch (DataAccessException exception) {

            throw new OrganizationPersistenceException(
                    exception);
        }
    }

    private OrganizationTenantPlacementResult attachInTransaction(
            UUID tenantId,
            UUID destinationOrganizationId) {

        var destinationState =
                lockDestination(
                        destinationOrganizationId);

        if (destinationState.isEmpty()) {
            return OrganizationTenantPlacementResult.DESTINATION_NOT_FOUND;
        }

        if (destinationState.get()
                == OrganizationStatus.SUSPENDED) {

            return OrganizationTenantPlacementResult.DESTINATION_SUSPENDED;
        }

        var inserted =
                jdbcTemplate.update(
                        ATTACH_SQL,
                        tenantId,
                        destinationOrganizationId);

        if (inserted == 1) {
            return OrganizationTenantPlacementResult.ATTACHED;
        }

        var currentPlacement =
                findCurrentPlacement(
                        tenantId);

        if (currentPlacement.isPresent()
                && currentPlacement.get()
                        .organizationId()
                        .equals(
                                destinationOrganizationId)) {

            return OrganizationTenantPlacementResult.ALREADY_ATTACHED;
        }

        return OrganizationTenantPlacementResult.CONFLICT;
    }

    private OrganizationTenantPlacementResult moveInTransaction(
            UUID tenantId,
            UUID expectedSourceOrganizationId,
            UUID destinationOrganizationId) {

        var destinationState =
                lockDestination(
                        destinationOrganizationId);

        if (destinationState.isEmpty()) {
            return OrganizationTenantPlacementResult.DESTINATION_NOT_FOUND;
        }

        if (destinationState.get()
                == OrganizationStatus.SUSPENDED) {

            return OrganizationTenantPlacementResult.DESTINATION_SUSPENDED;
        }

        var updated =
                jdbcTemplate.update(
                        MOVE_SQL,
                        destinationOrganizationId,
                        tenantId,
                        expectedSourceOrganizationId);

        if (updated == 1) {
            return OrganizationTenantPlacementResult.MOVED;
        }

        return OrganizationTenantPlacementResult.CONFLICT;
    }

    private OrganizationTenantPlacementResult detachInTransaction(
            UUID tenantId,
            UUID expectedOrganizationId) {

        var deleted =
                jdbcTemplate.update(
                        DETACH_SQL,
                        tenantId,
                        expectedOrganizationId);

        if (deleted == 1) {
            return OrganizationTenantPlacementResult.DETACHED;
        }

        if (findCurrentPlacement(
                tenantId)
                .isEmpty()) {

            return OrganizationTenantPlacementResult.ALREADY_UNASSIGNED;
        }

        return OrganizationTenantPlacementResult.CONFLICT;
    }

    private Optional<OrganizationStatus> lockDestination(
            UUID organizationId) {

        var states =
                jdbcTemplate.query(
                        LOCK_DESTINATION_SQL,
                        (resultSet, rowNumber) ->
                                OrganizationStatus.valueOf(
                                        resultSet.getString(
                                                "status")),
                        organizationId);

        return states.stream()
                .findFirst();
    }

    private Optional<OrganizationTenantPlacement> findCurrentPlacement(
            UUID tenantId) {

        var placements =
                jdbcTemplate.query(
                        FIND_BY_TENANT_SQL,
                        (resultSet, rowNumber) ->
                                OrganizationTenantPlacement.create(
                                        resultSet.getObject(
                                                "organization_id",
                                                UUID.class),
                                        resultSet.getObject(
                                                "tenant_id",
                                                UUID.class)),
                        tenantId);

        return placements.stream()
                .findFirst();
    }

    private static void validateRequiredId(
            UUID value,
            String message) {

        if (value == null) {
            throw new IllegalArgumentException(
                    message);
        }
    }
}
