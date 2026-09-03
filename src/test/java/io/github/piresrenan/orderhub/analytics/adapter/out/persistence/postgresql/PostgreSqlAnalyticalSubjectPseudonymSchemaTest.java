package io.github.piresrenan.orderhub.analytics.adapter.out.persistence.postgresql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class PostgreSqlAnalyticalSubjectPseudonymSchemaTest {

    private static final DockerImageName POSTGRES_IMAGE =
            DockerImageName.parse(
                    "postgres:18.6-trixie@sha256:"
                            + "4ef4dbc939d61acea57712655ddb4b4ab27419c913f94cca0cd57cb3ea3c2280")
                    .asCompatibleSubstituteFor("postgres");

    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(POSTGRES_IMAGE)
                    .withDatabaseName("orderhub_test")
                    .withUsername("orderhub_test")
                    .withPassword("synthetic-test-password");

    private static JdbcTemplate jdbcTemplate;

    @BeforeAll
    static void migrateAcceptedSchemaChain() {

        var dataSource =
                new DriverManagerDataSource(
                        POSTGRES.getJdbcUrl(),
                        POSTGRES.getUsername(),
                        POSTGRES.getPassword());

        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();

        jdbcTemplate =
                new JdbcTemplate(
                        dataSource);
    }

    @BeforeEach
    void resetPseudonymMappings() {

        jdbcTemplate.update(
                "DELETE FROM analytics.subject_pseudonyms");
    }

    private int insertMapping(
            UUID tenantId,
            UUID operationalSubjectId,
            UUID analyticalSubjectKey) {

        return jdbcTemplate.update(
                """
                INSERT INTO analytics.subject_pseudonyms (
                    tenant_id,
                    operational_subject_id,
                    analytical_subject_key
                )
                VALUES (?, ?, ?)
                """,
                tenantId,
                operationalSubjectId,
                analyticalSubjectKey);
    }

    @Test
    void v21ReconstructsThePseudonymMappingThroughTheFullFlywayChain() {
        // Why: a migration that only works against an already-populated
        // database is not a reconstructable schema.
        // Covers: V16 and V21 both present in the applied Flyway history after
        // migrating an empty PostgreSQL database.
        // Prevents: an analytics schema that cannot be rebuilt from scratch.

        var appliedVersions =
                jdbcTemplate.queryForList(
                        """
                        SELECT version
                        FROM flyway_schema_history
                        WHERE success = true
                          AND version IS NOT NULL
                        """,
                        String.class);

        assertThat(appliedVersions)
                .as("Accepted workforce evidence and the analytics mapping"
                        + " must both reconstruct from an empty database")
                .contains(
                        "16",
                        "21");
    }

    @Test
    void v21CreatesOnlyThePseudonymMappingRelation() {
        // Why: this slice must not freeze a fact or ingestion-cursor design
        // that is still deliberately undecided.
        // Covers: the complete table inventory of the analytics schema.
        // Prevents: a fact table, checkpoint or watermark relation entering
        // persistence before a test demonstrates the need for it.

        var tables =
                jdbcTemplate.queryForList(
                        """
                        SELECT table_name
                        FROM information_schema.tables
                        WHERE table_schema = 'analytics'
                        ORDER BY table_name
                        """,
                        String.class);

        assertThat(tables)
                .as("V21 must materialize exactly the pseudonym mapping")
                .containsExactly(
                        "subject_pseudonyms");
    }

    @Test
    void enforcesOneMappingPerTenantAndOperationalSubject() {
        // Why: a duplicated relationship would give one operational subject two
        // competing analytical identities inside the same Tenant.
        // Covers: uniqueness of (tenant_id, operational_subject_id).
        // Prevents: analytical facts for one subject splitting across two keys.

        var tenantId = UUID.randomUUID();
        var operationalSubjectId = UUID.randomUUID();

        assertThat(
                insertMapping(
                        tenantId,
                        operationalSubjectId,
                        UUID.randomUUID()))
                .isEqualTo(1);

        assertThatThrownBy(() ->
                insertMapping(
                        tenantId,
                        operationalSubjectId,
                        UUID.randomUUID()))
                .isInstanceOf(
                        DataIntegrityViolationException.class);
    }

    @Test
    void enforcesThatOneAnalyticalKeyRepresentsExactlyOneMapping() {
        // Why: this is what makes Tenant isolation a deterministic structural
        // property instead of a probabilistic one. Two mappings that both
        // persist therefore cannot share an analytical identity, whatever the
        // key generation strategy is.
        // Covers: uniqueness of analytical_subject_key across all mappings.
        // Prevents: one analytical key silently merging two subjects, or two
        // Tenants observing the same analytical identity.

        var analyticalSubjectKey = UUID.randomUUID();

        assertThat(
                insertMapping(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        analyticalSubjectKey))
                .isEqualTo(1);

        assertThatThrownBy(() ->
                insertMapping(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        analyticalSubjectKey))
                .isInstanceOf(
                        DataIntegrityViolationException.class);
    }

    @Test
    void rejectsAMappingMissingTenantSubjectOrAnalyticalKey() {
        // Why: a mapping row missing any of its three facts is not a usable
        // pseudonymous relationship.
        // Covers: NOT NULL on tenant_id, operational_subject_id and
        // analytical_subject_key.
        // Prevents: partially populated mapping rows that resolution would
        // later have to interpret.

        assertThatThrownBy(() ->
                insertMapping(
                        null,
                        UUID.randomUUID(),
                        UUID.randomUUID()))
                .isInstanceOf(
                        DataIntegrityViolationException.class);

        assertThatThrownBy(() ->
                insertMapping(
                        UUID.randomUUID(),
                        null,
                        UUID.randomUUID()))
                .isInstanceOf(
                        DataIntegrityViolationException.class);

        assertThatThrownBy(() ->
                insertMapping(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        null))
                .isInstanceOf(
                        DataIntegrityViolationException.class);
    }

    @Test
    void keepsThePseudonymMappingFreeOfOperationalForeignKeysAndExtraColumns() {
        // Why: a foreign key into users, workforce or another operational
        // schema would make analytics depend on operational persistence and
        // would let operational deletion dictate analytical state.
        // Covers: absence of any foreign key constraint, and the exact column
        // inventory of the mapping.
        // Prevents: cross-schema coupling and speculative lifecycle, status or
        // personal-data columns entering the mapping.

        var foreignKeys =
                jdbcTemplate.queryForList(
                        """
                        SELECT constraint_name
                        FROM information_schema.table_constraints
                        WHERE table_schema = 'analytics'
                          AND table_name = 'subject_pseudonyms'
                          AND constraint_type = 'FOREIGN KEY'
                        """,
                        String.class);

        assertThat(foreignKeys)
                .as("Analytics must not reference operational persistence")
                .isEmpty();

        var columns =
                jdbcTemplate.queryForList(
                        """
                        SELECT column_name
                        FROM information_schema.columns
                        WHERE table_schema = 'analytics'
                          AND table_name = 'subject_pseudonyms'
                        ORDER BY column_name
                        """,
                        String.class);

        assertThat(columns)
                .as("The mapping must carry exactly the three facts it needs")
                .containsExactly(
                        "analytical_subject_key",
                        "operational_subject_id",
                        "tenant_id");
    }
}
