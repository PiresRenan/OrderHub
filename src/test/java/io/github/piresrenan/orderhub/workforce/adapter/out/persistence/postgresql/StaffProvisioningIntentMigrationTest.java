package io.github.piresrenan.orderhub.workforce.adapter.out.persistence.postgresql;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class StaffProvisioningIntentMigrationTest {

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
            UUID.fromString("0f1e2d3c-4b5a-4968-8778-6a5b4c3d2e1f");

    private static final UUID TENANT_ID =
            UUID.fromString("11111111-2222-4333-8444-555555555555");

    private static final UUID ISSUED_BY_USER_ID =
            UUID.fromString("22222222-3333-4444-8555-666666666666");

    private static final UUID DEPARTMENT_ID =
            UUID.fromString("33333333-4444-4555-8666-777777777777");

    private static final UUID POSITION_ID =
            UUID.fromString("44444444-5555-4666-8777-888888888888");

    private static final UUID OPERATION_ID =
            UUID.fromString("55555555-6666-4777-8888-999999999999");

    private static final UUID CORRELATION_ID =
            UUID.fromString("66666666-7777-4888-8999-aaaaaaaaaaaa");

    private static final String INITIAL_ROLE_CODE =
            "OH19_SYNTHETIC_STAFF_BOOTSTRAP";

    private static final OffsetDateTime EXPIRES_AT =
            OffsetDateTime.parse("2099-01-01T00:00:00Z");

    @Test
    void migratesEmptyDatabaseIntoUsableStaffProvisioningIntentRelation() {
        // Why: a Staff invitee has no internal User and no external identity
        // binding yet, so the authorization to provision them must be durable,
        // Tenant-scoped, expiring, cancellable and consumable exactly once
        // before its target User exists.
        // Covers: the complete V1-V37 history migrating cleanly, owner-local
        // workforce placement fixtures, and the complete provisioning-intent
        // persistence contract that workforce must be able to store.
        // Prevents: Staff onboarding depending on direct database bootstrap, and
        // a provisioning authorization whose raw secret would have to be
        // recoverably persisted to survive a retry.

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

        var jdbcTemplate =
                new JdbcTemplate(
                        dataSource);

        assertThat(
                appliedMigrationCount(
                        jdbcTemplate,
                        "36"))
                .as("accepted OH-019 membership lifecycle migration V36 must apply")
                .isEqualTo(1);

        assertThat(
                appliedMigrationCount(
                        jdbcTemplate,
                        "37"))
                .as("the provisioning-intent migration V37 must apply exactly once"
                        + " from an empty database")
                .isEqualTo(1);

        assertThat(
                failedMigrationCount(
                        jdbcTemplate))
                .as("the full V1-V37 history must migrate without failure")
                .isZero();

        seedOwnerLocalPlacement(
                jdbcTemplate);

        assertThat(
                provisioningIntentRelationCount(
                        jdbcTemplate))
                .as("workforce must own a durable staff_provisioning_intents relation"
                        + " able to hold a one-time provisioning authorization that"
                        + " exists before its target User exists")
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
                    initial_role_code,
                    operation_id,
                    request_fingerprint,
                    expires_at,
                    correlation_id
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                INTENT_ID,
                TENANT_ID,
                syntheticDigest((byte) 0x11),
                ISSUED_BY_USER_ID,
                DEPARTMENT_ID,
                POSITION_ID,
                INITIAL_ROLE_CODE,
                OPERATION_ID,
                syntheticDigest((byte) 0x22),
                EXPIRES_AT,
                CORRELATION_ID);

        var persisted = jdbcTemplate.queryForMap(
                """
                SELECT
                    tenant_id,
                    issued_by_user_id,
                    department_id,
                    position_id,
                    initial_role_code,
                    operation_id,
                    correlation_id,
                    octet_length(secret_digest) AS secret_digest_length,
                    octet_length(request_fingerprint) AS request_fingerprint_length,
                    consumed_at,
                    cancelled_at,
                    created_at IS NOT NULL AS creation_recorded,
                    expires_at > created_at AS expiry_after_creation
                FROM workforce.staff_provisioning_intents
                WHERE intent_id = ?
                """,
                INTENT_ID);

        assertThat(persisted)
                .containsEntry("tenant_id", TENANT_ID)
                .containsEntry("issued_by_user_id", ISSUED_BY_USER_ID)
                .containsEntry("department_id", DEPARTMENT_ID)
                .containsEntry("position_id", POSITION_ID)
                .containsEntry("initial_role_code", INITIAL_ROLE_CODE)
                .containsEntry("operation_id", OPERATION_ID)
                .containsEntry("correlation_id", CORRELATION_ID);

        assertThat(persisted)
                .as("only a SHA-256 digest of the provisioning secret is durable")
                .containsEntry("secret_digest_length", 32)
                .containsEntry("request_fingerprint_length", 32);

        assertThat(persisted)
                .as("a newly issued intent is neither consumed nor cancelled")
                .containsEntry("consumed_at", null)
                .containsEntry("cancelled_at", null);

        assertThat(persisted)
                .as("the database owns creation time and expiry must follow it")
                .containsEntry("creation_recorded", true)
                .containsEntry("expiry_after_creation", true);
    }

    /**
     * Establishes the workforce-owned department and position the intent freezes,
     * so a successful consumption cannot later produce a Staff relationship
     * without an organizational placement.
     *
     * @param jdbcTemplate accepted-schema JDBC boundary
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
                VALUES (?, ?, 'PROVISIONING', 'Synthetic provisioning department')
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
                VALUES (?, ?, 'PROVISIONING', 'Synthetic provisioning position', 'OPERATIONAL')
                """,
                POSITION_ID,
                TENANT_ID);
    }

    /**
     * Counts the workforce relation that must hold durable provisioning intents.
     *
     * @param jdbcTemplate accepted-schema JDBC boundary
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
     * Counts successful applications of one accepted migration version.
     *
     * @param jdbcTemplate accepted-schema JDBC boundary
     * @param version accepted Flyway version
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
     * Counts any unsuccessful migration in the accepted history.
     *
     * @param jdbcTemplate accepted-schema JDBC boundary
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
