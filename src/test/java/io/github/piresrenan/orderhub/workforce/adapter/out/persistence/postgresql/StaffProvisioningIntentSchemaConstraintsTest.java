package io.github.piresrenan.orderhub.workforce.adapter.out.persistence.postgresql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class StaffProvisioningIntentSchemaConstraintsTest {

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

    private static final UUID PRIMARY_TENANT_ID =
            UUID.fromString("10000000-0000-4000-8000-000000000001");

    private static final UUID PRIMARY_DEPARTMENT_ID =
            UUID.fromString("10000000-0000-4000-8000-000000000002");

    private static final UUID PRIMARY_POSITION_ID =
            UUID.fromString("10000000-0000-4000-8000-000000000003");

    private static final UUID FOREIGN_TENANT_ID =
            UUID.fromString("20000000-0000-4000-8000-000000000001");

    private static final UUID FOREIGN_DEPARTMENT_ID =
            UUID.fromString("20000000-0000-4000-8000-000000000002");

    private static final UUID FOREIGN_POSITION_ID =
            UUID.fromString("20000000-0000-4000-8000-000000000003");

    private static final OffsetDateTime CREATED_AT =
            OffsetDateTime.parse("2030-01-01T00:00:00Z");

    private static final OffsetDateTime EXPIRES_AT =
            OffsetDateTime.parse("2030-01-02T00:00:00Z");

    private static JdbcTemplate jdbcTemplate;

    @BeforeAll
    static void migrateAcceptedSchemaChainAndSeedPlacements() {

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

        seedPlacement(
                PRIMARY_TENANT_ID,
                PRIMARY_DEPARTMENT_ID,
                PRIMARY_POSITION_ID,
                "PRIMARY");

        seedPlacement(
                FOREIGN_TENANT_ID,
                FOREIGN_DEPARTMENT_ID,
                FOREIGN_POSITION_ID,
                "FOREIGN");
    }

    @Test
    void acceptsOneCompleteUnresolvedProvisioningIntent() {
        // Why: the constraint surface must admit the exact shape a legitimate
        // provisioning authorization has when it is first issued.
        // Covers: 32-byte digest and fingerprint, optional role selector, and
        // terminal timestamps left unset.
        // Prevents: a constraint set so strict that no valid intent can exist.

        assertThatCode(() -> insert(
                intent()
                        .withDigestFiller((byte) 0x01)
                        .withInitialRoleCode("OH19_STAFF_BOOTSTRAP")))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsSecretDigestThatIsNotSha256Sized() {
        // Why: only a SHA-256 digest of the one-time secret is ever durable, so a
        // value of any other size cannot be a valid provisioning lookup identity.
        // Covers: the exact 32-octet digest constraint below and above the bound.
        // Prevents: a truncated or foreign digest silently becoming the future
        // single-use consumption key.

        assertThatThrownBy(() -> insert(
                intent()
                        .withSecretDigest(bytes((byte) 0x02, 31))))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining(
                        "ck_workforce_provisioning_intent_secret_digest_length");

        assertThatThrownBy(() -> insert(
                intent()
                        .withSecretDigest(bytes((byte) 0x03, 33))))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining(
                        "ck_workforce_provisioning_intent_secret_digest_length");
    }

    @Test
    void rejectsDuplicateSecretDigest() {
        // Why: the digest is the future consumption identity, so one digest
        // resolving to two authorizations would make single-use undecidable.
        // Covers: durable uniqueness of secret_digest.
        // Prevents: two provisioning intents being consumable by one secret.

        insert(
                intent()
                        .withDigestFiller((byte) 0x04));

        assertThatThrownBy(() -> insert(
                intent()
                        .withDigestFiller((byte) 0x04)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining(
                        "uq_workforce_provisioning_intent_secret_digest");
    }

    @Test
    void rejectsRequestFingerprintThatIsNotSha256Sized() {
        // Why: the fingerprint decides whether a repeated creation operation is a
        // replay or a conflicting request, so its width must be fixed.
        // Covers: the exact 32-octet request fingerprint constraint.
        // Prevents: an ambiguous fingerprint width weakening retry classification.

        assertThatThrownBy(() -> insert(
                intent()
                        .withDigestFiller((byte) 0x05)
                        .withRequestFingerprint(bytes((byte) 0x05, 16))))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining(
                        "ck_workforce_provisioning_intent_fingerprint_length");
    }

    @Test
    void rejectsRepeatedCreationOperationWithinOneTenant() {
        // Why: a retried creation command must never mint a second live
        // provisioning credential for the same Tenant.
        // Covers: durable uniqueness of (tenant_id, operation_id).
        // Prevents: a lost creation response producing two usable secrets.

        var operationId = UUID.randomUUID();

        insert(
                intent()
                        .withDigestFiller((byte) 0x06)
                        .withOperationId(operationId));

        assertThatThrownBy(() -> insert(
                intent()
                        .withDigestFiller((byte) 0x07)
                        .withOperationId(operationId)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining(
                        "uq_workforce_provisioning_intent_operation");
    }

    @Test
    void allowsTheSameOperationIdentityInAnotherTenant() {
        // Why: creation operation identity is Tenant-scoped, so two Tenants must
        // be able to use the same opaque operation identifier independently.
        // Covers: the operation uniqueness constraint being scoped to the Tenant.
        // Prevents: one Tenant's retry identity blocking provisioning elsewhere.

        var operationId = UUID.randomUUID();

        insert(
                intent()
                        .withDigestFiller((byte) 0x08)
                        .withOperationId(operationId));

        assertThatCode(() -> insert(
                intent()
                        .withDigestFiller((byte) 0x09)
                        .withOperationId(operationId)
                        .withTenant(
                                FOREIGN_TENANT_ID,
                                FOREIGN_DEPARTMENT_ID,
                                FOREIGN_POSITION_ID)))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsExpiryThatDoesNotFollowCreation() {
        // Why: an authorization that expires at or before it was issued was never
        // usable and must not be storable.
        // Covers: the expires_at > created_at invariant.
        // Prevents: a zero or negative validity window entering durable state.

        assertThatThrownBy(() -> insert(
                intent()
                        .withDigestFiller((byte) 0x0A)
                        .withExpiresAt(CREATED_AT)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining(
                        "ck_workforce_provisioning_intent_expiry");
    }

    @Test
    void rejectsAnIntentThatIsBothConsumedAndCancelled() {
        // Why: consumption and cancellation are mutually exclusive terminal facts.
        // Covers: the terminal-exclusivity invariant.
        // Prevents: an authorization whose resolution cannot be determined.

        assertThatThrownBy(() -> insert(
                intent()
                        .withDigestFiller((byte) 0x0B)
                        .withConsumedAt(CREATED_AT.plusHours(1))
                        .withCancelledAt(CREATED_AT.plusHours(2))))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining(
                        "ck_workforce_provisioning_intent_terminal_exclusive");
    }

    @Test
    void rejectsTerminalTimestampsThatPrecedeCreation() {
        // Why: an authorization cannot be resolved before it was issued.
        // Covers: consumed_at and cancelled_at chronology against created_at.
        // Prevents: back-dated evidence of consumption or revocation.

        assertThatThrownBy(() -> insert(
                intent()
                        .withDigestFiller((byte) 0x0C)
                        .withConsumedAt(CREATED_AT.minusSeconds(1))))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining(
                        "ck_workforce_provisioning_intent_consumed_after_creation");

        assertThatThrownBy(() -> insert(
                intent()
                        .withDigestFiller((byte) 0x0D)
                        .withCancelledAt(CREATED_AT.minusSeconds(1))))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining(
                        "ck_workforce_provisioning_intent_cancelled_after_creation");
    }

    @Test
    void rejectsConsumptionAtOrAfterExpiry() {
        // Why: an expired provisioning authorization must never be recorded as
        // successfully consumed, whatever the application later attempts.
        // Covers: consumed_at strictly before expires_at.
        // Prevents: durable evidence of a consumption that policy forbids.

        assertThatThrownBy(() -> insert(
                intent()
                        .withDigestFiller((byte) 0x0E)
                        .withConsumedAt(EXPIRES_AT)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining(
                        "ck_workforce_provisioning_intent_consumed_before_expiry");

        assertThatThrownBy(() -> insert(
                intent()
                        .withDigestFiller((byte) 0x0F)
                        .withConsumedAt(EXPIRES_AT.plusSeconds(1))))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining(
                        "ck_workforce_provisioning_intent_consumed_before_expiry");
    }

    @Test
    void allowsCancellationOfAnAlreadyExpiredIntent() {
        // Why: an administrator may still need to revoke an unresolved
        // authorization after its validity window has closed.
        // Covers: cancellation deliberately not being bounded by expiry.
        // Prevents: an over-strict constraint blocking legitimate revocation
        // evidence for an expired intent.

        assertThatCode(() -> insert(
                intent()
                        .withDigestFiller((byte) 0x10)
                        .withCancelledAt(EXPIRES_AT.plusDays(1))))
                .doesNotThrowAnyException();
    }

    @Test
    void constrainsTheOptionalBootstrapRoleSelectorToAcceptedVocabulary() {
        // Why: the role code is an opaque selector resolved later by authorization,
        // so only its accepted shape can be enforced here.
        // Covers: absent selector, accepted selector and malformed selector.
        // Prevents: arbitrary text entering a field a later authorization owner
        // will resolve.

        assertThatCode(() -> insert(
                intent()
                        .withDigestFiller((byte) 0x11)
                        .withInitialRoleCode(null)))
                .doesNotThrowAnyException();

        assertThatCode(() -> insert(
                intent()
                        .withDigestFiller((byte) 0x12)
                        .withInitialRoleCode("OH19_TENANT_STAFF")))
                .doesNotThrowAnyException();

        assertThatThrownBy(() -> insert(
                intent()
                        .withDigestFiller((byte) 0x13)
                        .withInitialRoleCode("lowercase_role")))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining(
                        "ck_workforce_provisioning_intent_role_code");
    }

    @Test
    void rejectsPlacementBorrowedFromAnotherTenant() {
        // Why: a provisioning intent freezes the Staff placement, so it must never
        // be able to reference another Tenant's department or position.
        // Covers: both owner-local composite foreign keys.
        // Prevents: cross-Tenant provisioning through a borrowed placement.

        assertThatThrownBy(() -> insert(
                intent()
                        .withDigestFiller((byte) 0x14)
                        .withDepartmentId(FOREIGN_DEPARTMENT_ID)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining(
                        "fk_workforce_provisioning_intent_department_scope");

        assertThatThrownBy(() -> insert(
                intent()
                        .withDigestFiller((byte) 0x15)
                        .withPositionId(FOREIGN_POSITION_ID)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining(
                        "fk_workforce_provisioning_intent_position_scope");
    }

    @Test
    void ownsNoRelationalCouplingToOtherModules() {
        // Why: Tenant, issuing User and role selector are cross-module identities
        // whose existence is proven through application contracts, not the schema.
        // Covers: PostgreSQL catalog metadata for every foreign key declared by
        // the provisioning relation.
        // Prevents: a provisioning shortcut creating database-level ownership
        // coupling to users, tenants or access_control.

        var foreignReferences =
                jdbcTemplate.queryForList(
                        """
                        SELECT target_namespace.nspname
                        FROM pg_constraint constraint_definition
                        JOIN pg_class source_table
                          ON source_table.oid = constraint_definition.conrelid
                        JOIN pg_namespace source_namespace
                          ON source_namespace.oid = source_table.relnamespace
                        JOIN pg_class target_table
                          ON target_table.oid = constraint_definition.confrelid
                        JOIN pg_namespace target_namespace
                          ON target_namespace.oid = target_table.relnamespace
                        WHERE constraint_definition.contype = 'f'
                          AND source_namespace.nspname = 'workforce'
                          AND source_table.relname = 'staff_provisioning_intents'
                          AND target_namespace.nspname <> 'workforce'
                        """,
                        String.class);

        assertThat(foreignReferences)
                .as("the provisioning relation must reference only workforce relations")
                .isEmpty();
    }

    @Test
    void ownerLocalPlacementReferencesDoNotCascadeDeletion() {
        // Why: removing a department or position must not silently destroy a
        // pending or historical provisioning authorization.
        // Covers: the declared delete action of both owner-local foreign keys.
        // Prevents: organizational cleanup erasing privileged provisioning
        // evidence instead of failing closed.

        var deleteActions =
                jdbcTemplate.queryForList(
                        """
                        SELECT constraint_definition.confdeltype
                        FROM pg_constraint constraint_definition
                        JOIN pg_class source_table
                          ON source_table.oid = constraint_definition.conrelid
                        JOIN pg_namespace source_namespace
                          ON source_namespace.oid = source_table.relnamespace
                        WHERE constraint_definition.contype = 'f'
                          AND source_namespace.nspname = 'workforce'
                          AND source_table.relname = 'staff_provisioning_intents'
                        """,
                        String.class);

        assertThat(deleteActions)
                .as("both owner-local placement references must exist and fail closed")
                .hasSize(2)
                .containsOnly("a");
    }

    private static void seedPlacement(
            UUID tenantId,
            UUID departmentId,
            UUID positionId,
            String code) {

        jdbcTemplate.update(
                """
                INSERT INTO workforce.departments (
                    department_id,
                    tenant_id,
                    code,
                    name
                )
                VALUES (?, ?, ?, 'Synthetic provisioning department')
                """,
                departmentId,
                tenantId,
                code);

        jdbcTemplate.update(
                """
                INSERT INTO workforce.job_positions (
                    position_id,
                    tenant_id,
                    code,
                    title,
                    authority_band
                )
                VALUES (?, ?, ?, 'Synthetic provisioning position', 'OPERATIONAL')
                """,
                positionId,
                tenantId,
                code);
    }

    private static void insert(
            IntentRow row) {

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
                    consumed_at,
                    cancelled_at,
                    correlation_id,
                    created_at
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                row.intentId,
                row.tenantId,
                row.secretDigest,
                row.issuedByUserId,
                row.departmentId,
                row.positionId,
                row.initialRoleCode,
                row.operationId,
                row.requestFingerprint,
                row.expiresAt,
                row.consumedAt,
                row.cancelledAt,
                row.correlationId,
                row.createdAt);
    }

    private static IntentRow intent() {
        return new IntentRow();
    }

    /**
     * Produces a deterministic fixed-width stand-in for a digest.
     *
     * <p>
     * No provisioning secret is generated, derived or exposed by this test.
     * </p>
     *
     * @param filler deterministic byte value
     * @param length number of bytes to produce
     * @return synthetic digest-shaped value
     */
    private static byte[] bytes(
            byte filler,
            int length) {

        var value = new byte[length];
        Arrays.fill(value, filler);

        return value;
    }

    /** Mutable synthetic provisioning-intent row used to isolate one invariant. */
    private static final class IntentRow {

        private UUID intentId = UUID.randomUUID();
        private UUID tenantId = PRIMARY_TENANT_ID;
        private byte[] secretDigest = bytes((byte) 0x7f, 32);
        private UUID issuedByUserId = UUID.randomUUID();
        private UUID departmentId = PRIMARY_DEPARTMENT_ID;
        private UUID positionId = PRIMARY_POSITION_ID;
        private String initialRoleCode;
        private UUID operationId = UUID.randomUUID();
        private byte[] requestFingerprint = bytes((byte) 0x6f, 32);
        private OffsetDateTime expiresAt = EXPIRES_AT;
        private OffsetDateTime consumedAt;
        private OffsetDateTime cancelledAt;
        private UUID correlationId = UUID.randomUUID();
        private OffsetDateTime createdAt = CREATED_AT;

        private IntentRow withDigestFiller(byte filler) {
            this.secretDigest = bytes(filler, 32);
            return this;
        }

        private IntentRow withSecretDigest(byte[] digest) {
            this.secretDigest = digest;
            return this;
        }

        private IntentRow withRequestFingerprint(byte[] fingerprint) {
            this.requestFingerprint = fingerprint;
            return this;
        }

        private IntentRow withOperationId(UUID operationId) {
            this.operationId = operationId;
            return this;
        }

        private IntentRow withInitialRoleCode(String initialRoleCode) {
            this.initialRoleCode = initialRoleCode;
            return this;
        }

        private IntentRow withExpiresAt(OffsetDateTime expiresAt) {
            this.expiresAt = expiresAt;
            return this;
        }

        private IntentRow withConsumedAt(OffsetDateTime consumedAt) {
            this.consumedAt = consumedAt;
            return this;
        }

        private IntentRow withCancelledAt(OffsetDateTime cancelledAt) {
            this.cancelledAt = cancelledAt;
            return this;
        }

        private IntentRow withDepartmentId(UUID departmentId) {
            this.departmentId = departmentId;
            return this;
        }

        private IntentRow withPositionId(UUID positionId) {
            this.positionId = positionId;
            return this;
        }

        private IntentRow withTenant(
                UUID tenantId,
                UUID departmentId,
                UUID positionId) {

            this.tenantId = tenantId;
            this.departmentId = departmentId;
            this.positionId = positionId;
            return this;
        }
    }
}
