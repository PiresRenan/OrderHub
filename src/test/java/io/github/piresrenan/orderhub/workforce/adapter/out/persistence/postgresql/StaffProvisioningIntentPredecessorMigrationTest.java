package io.github.piresrenan.orderhub.workforce.adapter.out.persistence.postgresql;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class StaffProvisioningIntentPredecessorMigrationTest {

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

    private static final UUID INTENT_ID =
            UUID.fromString("7a6b5c4d-3e2f-4a1b-8c9d-0e1f2a3b4c5d");

    private static final UUID TENANT_ID =
            UUID.fromString("aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee");

    private static final UUID ISSUED_BY_USER_ID =
            UUID.fromString("bbbbbbbb-cccc-4ddd-8eee-ffffffffffff");

    private static final UUID DEPARTMENT_ID =
            UUID.fromString("cccccccc-dddd-4eee-8fff-000000000001");

    private static final UUID POSITION_ID =
            UUID.fromString("dddddddd-eeee-4fff-8000-000000000002");

    private static final UUID OPERATION_ID =
            UUID.fromString("eeeeeeee-ffff-4000-8111-000000000003");

    private static final UUID CORRELATION_ID =
            UUID.fromString("ffffffff-0000-4111-8222-000000000004");

    private static final OffsetDateTime EXPIRES_AT =
            OffsetDateTime.parse("2099-01-01T00:00:00Z");

    @Test
    void upgradesAcceptedV36DatabaseWithStaffProvisioningIntentRelation() {
        // Why: OrderHub upgrades running databases, so the provisioning primitive
        // must be reachable from the accepted predecessor rather than only from
        // a freshly created schema.
        // Covers: the real V36 -> V37 transition, the relation being absent
        // beforehand, and the upgraded relation accepting a complete intent whose
        // owner-local placement was seeded while still on V36.
        // Prevents: a migration that only works on an empty database, and an
        // upgrade that silently leaves the provisioning relation unusable.

        var dataSource =
                new DriverManagerDataSource(
                        POSTGRES.getJdbcUrl(),
                        POSTGRES.getUsername(),
                        POSTGRES.getPassword());

        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion("36"))
                .load()
                .migrate();

        var jdbcTemplate =
                new JdbcTemplate(
                        dataSource);

        assertThat(
                appliedMigrationCount(
                        jdbcTemplate,
                        "36"))
                .as("the accepted predecessor V36 must apply")
                .isEqualTo(1);

        assertThat(
                provisioningIntentRelationCount(
                        jdbcTemplate))
                .as("accepted V36 cannot already contain the provisioning relation")
                .isZero();

        seedOwnerLocalPlacement(
                jdbcTemplate);

        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();

        assertThat(
                appliedMigrationCount(
                        jdbcTemplate,
                        "37"))
                .as("V37 must apply exactly once when upgrading from V36")
                .isEqualTo(1);

        assertThat(
                failedMigrationCount(
                        jdbcTemplate))
                .as("the V36 -> V37 upgrade must not leave a failed migration")
                .isZero();

        assertThat(
                provisioningIntentRelationCount(
                        jdbcTemplate))
                .isEqualTo(1);

        jdbcTemplate.update(
                """
                INSERT INTO workforce.staff_provisioning_intents (
                    intent_id,
                    tenant_id,
                    secret_digest,
                    issued_by_user_id,
                    department_id,
                    position_id,
                    operation_id,
                    request_fingerprint,
                    expires_at,
                    correlation_id
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                INTENT_ID,
                TENANT_ID,
                syntheticDigest((byte) 0x33),
                ISSUED_BY_USER_ID,
                DEPARTMENT_ID,
                POSITION_ID,
                OPERATION_ID,
                syntheticDigest((byte) 0x44),
                EXPIRES_AT,
                CORRELATION_ID);

        var persisted = jdbcTemplate.queryForMap(
                """
                SELECT
                    tenant_id,
                    department_id,
                    position_id,
                    initial_role_code,
                    octet_length(secret_digest) AS secret_digest_length,
                    consumed_at,
                    cancelled_at,
                    expires_at > created_at AS expiry_after_creation
                FROM workforce.staff_provisioning_intents
                WHERE intent_id = ?
                """,
                INTENT_ID);

        assertThat(persisted)
                .as("an upgraded database stores the placement frozen while on V36")
                .containsEntry("tenant_id", TENANT_ID)
                .containsEntry("department_id", DEPARTMENT_ID)
                .containsEntry("position_id", POSITION_ID);

        assertThat(persisted)
                .as("the bootstrap role selector is optional")
                .containsEntry("initial_role_code", null);

        assertThat(persisted)
                .containsEntry("secret_digest_length", 32)
                .containsEntry("consumed_at", null)
                .containsEntry("cancelled_at", null)
                .containsEntry("expiry_after_creation", true);
    }

    /**
     * Establishes the workforce-owned placement on the accepted predecessor so
     * the upgraded relation references state that already existed before V37.
     *
     * @param jdbcTemplate predecessor-schema JDBC boundary
     */
    private static void seedOwnerLocalPlacement(
            JdbcTemplate jdbcTemplate) {

        jdbcTemplate.update(
                """
                INSERT INTO workforce.departments (
                    department_id,
                    tenant_id,
                    code,
                    name
                )
                VALUES (?, ?, 'UPGRADE', 'Synthetic upgrade department')
                """,
                DEPARTMENT_ID,
                TENANT_ID);

        jdbcTemplate.update(
                """
                INSERT INTO workforce.job_positions (
                    position_id,
                    tenant_id,
                    code,
                    title,
                    authority_band
                )
                VALUES (?, ?, 'UPGRADE', 'Synthetic upgrade position', 'OPERATIONAL')
                """,
                POSITION_ID,
                TENANT_ID);
    }

    /**
     * Counts the workforce relation that holds durable provisioning intents.
     *
     * @param jdbcTemplate schema JDBC boundary
     * @return one when the relation exists, otherwise zero
     */
    private static int provisioningIntentRelationCount(
            JdbcTemplate jdbcTemplate) {

        return jdbcTemplate.queryForObject(
                """
                SELECT count(*)
                FROM information_schema.tables
                WHERE table_schema = 'workforce'
                  AND table_name = 'staff_provisioning_intents'
                """,
                Integer.class);
    }

    /**
     * Counts successful applications of one migration version.
     *
     * @param jdbcTemplate schema JDBC boundary
     * @param version Flyway version
     * @return number of successful applications
     */
    private static int appliedMigrationCount(
            JdbcTemplate jdbcTemplate,
            String version) {

        return jdbcTemplate.queryForObject(
                """
                SELECT count(*)
                FROM public.flyway_schema_history
                WHERE version = ?
                  AND success = TRUE
                """,
                Integer.class,
                version);
    }

    /**
     * Counts any unsuccessful migration in the applied history.
     *
     * @param jdbcTemplate schema JDBC boundary
     * @return number of failed migrations
     */
    private static int failedMigrationCount(
            JdbcTemplate jdbcTemplate) {

        return jdbcTemplate.queryForObject(
                """
                SELECT count(*)
                FROM public.flyway_schema_history
                WHERE success = FALSE
                """,
                Integer.class);
    }

    /**
     * Produces a deterministic 32-byte stand-in for a SHA-256 digest.
     *
     * <p>
     * No provisioning secret is generated, derived or exposed by this test.
     * </p>
     *
     * @param filler deterministic byte value
     * @return synthetic digest-shaped value
     */
    private static byte[] syntheticDigest(
            byte filler) {

        var digest = new byte[32];
        java.util.Arrays.fill(digest, filler);

        return digest;
    }
}
