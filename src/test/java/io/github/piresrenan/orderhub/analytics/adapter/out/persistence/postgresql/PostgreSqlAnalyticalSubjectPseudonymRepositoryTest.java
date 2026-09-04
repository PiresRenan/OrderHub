package io.github.piresrenan.orderhub.analytics.adapter.out.persistence.postgresql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import io.github.piresrenan.orderhub.analytics.application.port.out.AnalyticalSubjectPseudonymRepository;
import io.github.piresrenan.orderhub.analytics.domain.model.AnalyticalSubjectKey;

@Testcontainers
class PostgreSqlAnalyticalSubjectPseudonymRepositoryTest {

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

    private AnalyticalSubjectPseudonymRepository repository;

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

        repository =
                new PostgreSqlAnalyticalSubjectPseudonymRepository(
                        jdbcTemplate);
    }

    private void seedMapping(
            UUID tenantId,
            UUID operationalSubjectId,
            UUID analyticalSubjectKey) {

        jdbcTemplate.update(
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

    private UUID persistedKey(
            UUID tenantId,
            UUID operationalSubjectId) {

        return jdbcTemplate.queryForObject(
                """
                SELECT analytical_subject_key
                FROM analytics.subject_pseudonyms
                WHERE tenant_id = ?
                  AND operational_subject_id = ?
                """,
                UUID.class,
                tenantId,
                operationalSubjectId);
    }

    private int rowCountForOperationalSubject(
            UUID operationalSubjectId) {

        return jdbcTemplate.queryForObject(
                """
                SELECT count(*)
                FROM analytics.subject_pseudonyms
                WHERE operational_subject_id = ?
                """,
                Integer.class,
                operationalSubjectId);
    }

    @Test
    void createsAndReturnsPersistedMappingWhenMissing() {
        // Why: without a durable mapping the analytical identity would exist
        // only in memory and could not correlate anything across resolutions.
        // Covers: creation of the mapping row and agreement between the
        // returned key and the persisted key.
        // Prevents: a resolver that returns an identity it never durably
        // established.

        var tenantId = UUID.randomUUID();
        var operationalSubjectId = UUID.randomUUID();

        var resolved =
                repository.resolveOrCreate(
                        tenantId,
                        operationalSubjectId);

        assertThat(resolved)
                .as("Resolution must yield an analytical subject key")
                .isNotNull();

        assertThat(rowCountForOperationalSubject(
                operationalSubjectId))
                .as("Exactly one mapping must be established")
                .isEqualTo(1);

        assertThat(resolved)
                .as("The returned key must be the persisted key")
                .isEqualTo(
                        new AnalyticalSubjectKey(
                                persistedKey(
                                        tenantId,
                                        operationalSubjectId)));
    }

    @Test
    void returnsExistingMappingWithoutReplacingIt() {
        // Why: regenerating the analytical identity of a known subject would
        // silently break correlation with every fact already retained under
        // the previous key.
        // Covers: resolution of an already-persisted mapping.
        // Prevents: an upsert that overwrites a durable analytical identity.

        var tenantId = UUID.randomUUID();
        var operationalSubjectId = UUID.randomUUID();
        var existingKey = UUID.randomUUID();

        seedMapping(
                tenantId,
                operationalSubjectId,
                existingKey);

        var resolved =
                repository.resolveOrCreate(
                        tenantId,
                        operationalSubjectId);

        assertThat(resolved)
                .as("The already-persisted identity must be returned")
                .isEqualTo(
                        new AnalyticalSubjectKey(
                                existingKey));

        assertThat(persistedKey(
                tenantId,
                operationalSubjectId))
                .as("The persisted identity must not be rewritten")
                .isEqualTo(existingKey);

        assertThat(rowCountForOperationalSubject(
                operationalSubjectId))
                .as("Resolution must not create a second mapping")
                .isEqualTo(1);
    }

    @Test
    void repeatedSequentialResolutionKeepsOneStableMapping() {
        // Why: durable resolution is only useful if it is stable across
        // repeated calls; an unstable resolver would fragment one subject
        // across several analytical identities.
        // Covers: three sequential resolutions of the same tuple.
        // Prevents: a resolver that inserts on every call or returns a fresh
        // identity each time.

        var tenantId = UUID.randomUUID();
        var operationalSubjectId = UUID.randomUUID();

        var first =
                repository.resolveOrCreate(
                        tenantId,
                        operationalSubjectId);

        var second =
                repository.resolveOrCreate(
                        tenantId,
                        operationalSubjectId);

        var third =
                repository.resolveOrCreate(
                        tenantId,
                        operationalSubjectId);

        assertThat(second)
                .isEqualTo(first);

        assertThat(third)
                .isEqualTo(first);

        assertThat(rowCountForOperationalSubject(
                operationalSubjectId))
                .as("Repeated resolution must keep exactly one mapping")
                .isEqualTo(1);
    }

    @Test
    void resolvesTheSameOperationalSubjectIndependentlyPerTenant() {
        // Why: an adapter that looked the mapping up by operational subject
        // alone would hand one Tenant another Tenant's analytical identity.
        // Covers: a seeded mapping in one Tenant, resolution of the same
        // operational subject in a second Tenant, and the resulting row
        // population.
        // Prevents: a Tenant-blind lookup. Such an adapter would return the
        // seeded key for the second Tenant and would create no second row, so
        // both assertions below fail deterministically rather than by chance.

        var tenantA = UUID.randomUUID();
        var tenantB = UUID.randomUUID();
        var sharedOperationalSubjectId = UUID.randomUUID();
        var tenantAKey = UUID.randomUUID();

        seedMapping(
                tenantA,
                sharedOperationalSubjectId,
                tenantAKey);

        var resolvedForB =
                repository.resolveOrCreate(
                        tenantB,
                        sharedOperationalSubjectId);

        assertThat(resolvedForB)
                .as("A Tenant must never receive another Tenant's analytical"
                        + " identity")
                .isNotEqualTo(
                        new AnalyticalSubjectKey(
                                tenantAKey));

        assertThat(rowCountForOperationalSubject(
                sharedOperationalSubjectId))
                .as("Each Tenant must hold its own independent mapping")
                .isEqualTo(2);

        assertThat(resolvedForB)
                .as("The second Tenant must resolve to its own persisted row")
                .isEqualTo(
                        new AnalyticalSubjectKey(
                                persistedKey(
                                        tenantB,
                                        sharedOperationalSubjectId)));

        assertThat(
                repository.resolveOrCreate(
                        tenantA,
                        sharedOperationalSubjectId))
                .as("The first Tenant must still resolve to its seeded row")
                .isEqualTo(
                        new AnalyticalSubjectKey(
                                tenantAKey));

        assertThat(rowCountForOperationalSubject(
                sharedOperationalSubjectId))
                .as("Resolving the first Tenant again must not add a mapping")
                .isEqualTo(2);
    }

    @Test
    void rejectsResolutionWithoutTenantScopeOrOperationalSubject() {
        // Why: a missing input must fail as a caller error at the boundary
        // rather than reaching PostgreSQL and surfacing as a persistence
        // failure.
        // Covers: absent Tenant scope and absent operational subject, using
        // the IllegalArgumentException convention already established across
        // analytics domain contracts.
        // Prevents: null propagating into SQL, where it would appear as a
        // NOT NULL violation instead of a rejected request, and prevents a
        // partial row being attempted at all.

        var tenantId = UUID.randomUUID();
        var operationalSubjectId = UUID.randomUUID();

        assertThatThrownBy(() ->
                repository.resolveOrCreate(
                        null,
                        operationalSubjectId))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() ->
                repository.resolveOrCreate(
                        tenantId,
                        null))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(rowCountForOperationalSubject(
                operationalSubjectId))
                .as("A rejected resolution must persist nothing")
                .isZero();
    }

    /**
     * Test-only JdbcTemplate that holds both concurrent resolutions at the
     * exact point where each has already observed that no mapping exists.
     *
     * <p>
     * The coordination is a property of this test, not of production. It
     * removes scheduler luck from the reproduction: the interleaving is forced
     * rather than awaited.
     * </p>
     */
    private static final class LookupCoordinatingJdbcTemplate
            extends JdbcTemplate {

        private static final String MAPPING_LOOKUP_MARKER =
                "FROM analytics.subject_pseudonyms";

        private final CountDownLatch bothObservedAbsence;

        private final AtomicInteger absentLookups;

        LookupCoordinatingJdbcTemplate(
                DataSource dataSource,
                CountDownLatch bothObservedAbsence,
                AtomicInteger absentLookups) {

            super(dataSource);

            this.bothObservedAbsence = bothObservedAbsence;
            this.absentLookups = absentLookups;
        }

        @Override
        public <T> List<T> queryForList(
                String sql,
                Class<T> elementType,
                Object... args) {

            var result =
                    super.queryForList(
                            sql,
                            elementType,
                            args);

            if (!result.isEmpty()
                    || !sql.contains(MAPPING_LOOKUP_MARKER)) {

                return result;
            }

            absentLookups.incrementAndGet();
            bothObservedAbsence.countDown();

            try {
                if (!bothObservedAbsence.await(
                        10,
                        TimeUnit.SECONDS)) {

                    throw new IllegalStateException(
                            "Both concurrent lookups did not reach the"
                                    + " coordination point");
                }

            } catch (InterruptedException interrupted) {

                Thread.currentThread().interrupt();

                throw new IllegalStateException(
                        "Interrupted while coordinating concurrent lookups",
                        interrupted);
            }

            return result;
        }
    }

    @Test
    void resolvesConcurrentlyToOneStableIdentityForTheSameTuple()
            throws Exception {
        // Why: two callers can resolve the same subject at the same time. If
        // resolution is not arbitrated, one caller receives a persistence
        // failure for a subject that another caller resolved successfully.
        // Covers: two real threads whose lookups are both released only after
        // both have observed that no mapping exists.
        // Prevents: a lost or duplicated analytical identity, and a uniqueness
        // violation escaping to the caller, under concurrent first resolution
        // of one tuple.

        var tenantId = UUID.randomUUID();
        var operationalSubjectId = UUID.randomUUID();

        var bothObservedAbsence =
                new CountDownLatch(2);

        var absentLookups =
                new AtomicInteger();

        var coordinatedRepository =
                new PostgreSqlAnalyticalSubjectPseudonymRepository(
                        new LookupCoordinatingJdbcTemplate(
                                jdbcTemplate.getDataSource(),
                                bothObservedAbsence,
                                absentLookups));

        var executor =
                Executors.newFixedThreadPool(2);

        try {
            var first =
                    executor.submit(() ->
                            coordinatedRepository.resolveOrCreate(
                                    tenantId,
                                    operationalSubjectId));

            var second =
                    executor.submit(() ->
                            coordinatedRepository.resolveOrCreate(
                                    tenantId,
                                    operationalSubjectId));

            var firstKey =
                    first.get(
                            30,
                            TimeUnit.SECONDS);

            var secondKey =
                    second.get(
                            30,
                            TimeUnit.SECONDS);

            assertThat(absentLookups.get())
                    .as("Both resolutions must have observed absence before"
                            + " either was released")
                    .isEqualTo(2);

            assertThat(firstKey)
                    .as("Concurrent resolution must yield one identity")
                    .isEqualTo(secondKey);

            assertThat(rowCountForOperationalSubject(
                    operationalSubjectId))
                    .as("Exactly one mapping must survive concurrent"
                            + " resolution")
                    .isEqualTo(1);

            assertThat(persistedKey(
                    tenantId,
                    operationalSubjectId))
                    .as("The surviving mapping must hold the identity both"
                            + " callers received")
                    .isEqualTo(firstKey.value());

        } finally {
            executor.shutdownNow();
        }
    }
}
