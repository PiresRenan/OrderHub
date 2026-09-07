package io.github.piresrenan.orderhub.organizations.adapter.out.persistence.postgresql;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import io.github.piresrenan.orderhub.organizations.application.port.out.OrganizationPersistenceException;
import io.github.piresrenan.orderhub.organizations.application.port.out.OrganizationRepository;
import io.github.piresrenan.orderhub.organizations.domain.model.Organization;
import io.github.piresrenan.orderhub.organizations.domain.model.OrganizationStatus;

public final class PostgreSqlOrganizationRepository
        implements OrganizationRepository {

    private static final String INSERT_SQL = """
            INSERT INTO organizations.organizations (
                id,
                name,
                status
            )
            VALUES (?, ?, ?)
            """;

    private static final String FIND_BY_ID_SQL = """
            SELECT
                id,
                name,
                status
            FROM organizations.organizations
            WHERE id = ?
            """;

    private static final String FIND_ALL_SQL = """
            SELECT id, name, status
            FROM organizations.organizations
            ORDER BY name, id
            """;

    private final JdbcTemplate jdbcTemplate;

    public PostgreSqlOrganizationRepository(
            JdbcTemplate jdbcTemplate) {

        if (jdbcTemplate == null) {
            throw new IllegalArgumentException(
                    "JDBC template is required");
        }

        this.jdbcTemplate =
                jdbcTemplate;
    }

    @Override
    public Organization save(
            Organization organization) {

        try {
            jdbcTemplate.update(
                    INSERT_SQL,
                    organization.id(),
                    organization.name(),
                    organization.status().name());

            return organization;

        } catch (DataAccessException exception) {

            throw new OrganizationPersistenceException(
                    exception);
        }
    }

    @Override
    public Optional<Organization> findById(
            UUID organizationId) {

        try {
            var organizations =
                    jdbcTemplate.query(
                            FIND_BY_ID_SQL,
                            (resultSet, rowNumber) ->
                                    Organization.rehydrate(
                                            resultSet.getObject(
                                                    "id",
                                                    UUID.class),
                                            resultSet.getString(
                                                    "name"),
                                            OrganizationStatus.valueOf(
                                                    resultSet.getString(
                                                            "status"))),
                            organizationId);

            return organizations.stream()
                    .findFirst();

        } catch (DataAccessException exception) {

            throw new OrganizationPersistenceException(
                    exception);
        }
    }

    @Override
    public List<Organization> findAll() {
        try {
            return jdbcTemplate.query(
                    FIND_ALL_SQL,
                    (resultSet, rowNumber) -> Organization.rehydrate(
                            resultSet.getObject("id", UUID.class),
                            resultSet.getString("name"),
                            OrganizationStatus.valueOf(resultSet.getString("status"))));
        } catch (DataAccessException exception) {
            throw new OrganizationPersistenceException(exception);
        }
    }
}
