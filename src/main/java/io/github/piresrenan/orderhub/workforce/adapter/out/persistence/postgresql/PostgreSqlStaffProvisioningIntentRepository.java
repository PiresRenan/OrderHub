package io.github.piresrenan.orderhub.workforce.adapter.out.persistence.postgresql;

import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Optional;
import java.util.List;
import java.util.UUID;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import io.github.piresrenan.orderhub.workforce.application.model.ConsumedStaffProvisioningIntent;
import io.github.piresrenan.orderhub.workforce.application.model.NewStaffProvisioningIntent;
import io.github.piresrenan.orderhub.workforce.application.model.StaffProvisioningIntentCreation;
import io.github.piresrenan.orderhub.workforce.application.port.out.StaffProvisioningIntentPersistenceException;
import io.github.piresrenan.orderhub.workforce.application.port.out.StaffProvisioningIntentRepository;

/**
 * PostgreSQL authority for one-time Staff provisioning-intent consumption.
 *
 * <p>The terminal transition is a single conditional {@code UPDATE RETURNING}.
 * No pre-read is performed, so an already consumed, cancelled, expired,
 * not-yet-valid or unknown credential is represented by no returned row.</p>
 */
public final class PostgreSqlStaffProvisioningIntentRepository
        implements StaffProvisioningIntentRepository {

    private static final int SHA_256_BYTES = 32;

    private final JdbcTemplate jdbcTemplate;

    /** Requires the supplied owner contracts; construction performs no lifecycle mutation or independent commit. */
    public PostgreSqlStaffProvisioningIntentRepository(
            JdbcTemplate jdbcTemplate) {

        if (jdbcTemplate == null) {
            throw new IllegalArgumentException(
                    "JdbcTemplate is required");
        }

        this.jdbcTemplate =
                jdbcTemplate;
    }

    /** Persists frozen issuance facts or classifies an exact operation replay without returning the original secret. */
    @Override
    public StaffProvisioningIntentCreation create(
            NewStaffProvisioningIntent intent) {

        if (intent == null) {
            throw new IllegalArgumentException(
                    "Staff provisioning intent is required");
        }

        try {

            // Recognizing an already committed operation must not wait for an
            // unrelated terminal UPDATE of its intent. Waiting in the unique
            // index while holding issuer authority locks inverts consumption's
            // intent-before-authority order. This is issuance replay only;
            // consumption remains a single conditional UPDATE RETURNING.
            var existingOperation = findCreationIdentity(intent.tenantId(), intent.operationId());
            if (!existingOperation.isEmpty()) {
                return recognizeCreationReplay(existingOperation, intent.requestFingerprint());
            }

            var createdIntentIds =
                    jdbcTemplate.query(
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
                            VALUES (
                                ?,
                                ?,
                                ?,
                                ?,
                                ?,
                                ?,
                                ?,
                                ?,
                                ?,
                                ?,
                                ?
                            )
                            ON CONFLICT (
                                tenant_id,
                                operation_id
                            )
                            DO NOTHING
                            RETURNING intent_id
                            """,
                            (resultSet, rowNumber) ->
                                    resultSet.getObject(
                                            "intent_id",
                                            UUID.class),
                            intent.intentId(),
                            intent.tenantId(),
                            intent.secretDigest(),
                            intent.issuedByUserId(),
                            intent.departmentId(),
                            intent.positionId(),
                            intent.initialRoleCode(),
                            intent.operationId(),
                            intent.requestFingerprint(),
                            intent.expiresAt(),
                            intent.correlationId());

            if (createdIntentIds.size() > 1) {
                throw new StaffProvisioningIntentPersistenceException(
                        "Staff provisioning intent creation returned "
                                + "multiple durable rows",
                        null);
            }

            if (createdIntentIds.size() == 1) {

                return new StaffProvisioningIntentCreation.Created(
                        createdIntentIds.getFirst());
            }

            return recognizeCreationReplay(findCreationIdentity(intent.tenantId(), intent.operationId()),
                    intent.requestFingerprint());

        } catch (DataAccessException exception) {

            throw new StaffProvisioningIntentPersistenceException(
                    "Failed to create Staff provisioning intent",
                    exception);
        }
    }
    /** Atomically consumes only a pending valid proof; row-lock deadline enforcement prevents stale completion. */
    @Override
    public Optional<ConsumedStaffProvisioningIntent> consumePending(
            byte[] secretDigest,
            OffsetDateTime consumedAt) {

        requireDigest(
                secretDigest);

        if (consumedAt == null) {
            throw new IllegalArgumentException(
                    "Consumption time is required");
        }

        try {

            var consumed =
                    jdbcTemplate.query(
                            """
                            UPDATE workforce.staff_provisioning_intents
                            SET consumed_at = ?
                            WHERE secret_digest = ?
                              AND consumed_at IS NULL
                              AND cancelled_at IS NULL
                              AND created_at <= ?
                              AND expires_at > ?
                            RETURNING
                                intent_id,
                                tenant_id,
                                issued_by_user_id,
                                department_id,
                                position_id,
                                initial_role_code,
                                correlation_id
                            """,
                            (resultSet, rowNumber) ->
                                    new ConsumedStaffProvisioningIntent(
                                            resultSet.getObject(
                                                    "intent_id",
                                                    java.util.UUID.class),
                                            resultSet.getObject(
                                                    "tenant_id",
                                                    java.util.UUID.class),
                                            resultSet.getObject(
                                                    "issued_by_user_id",
                                                    java.util.UUID.class),
                                            resultSet.getObject(
                                                    "department_id",
                                                    java.util.UUID.class),
                                            resultSet.getObject(
                                                    "position_id",
                                                    java.util.UUID.class),
                                            resultSet.getString(
                                                    "initial_role_code"),
                                            resultSet.getObject(
                                                    "correlation_id",
                                                    java.util.UUID.class)),
                            consumedAt,
                            secretDigest,
                            consumedAt,
                            consumedAt);

            if (consumed.size() > 1) {
                throw new StaffProvisioningIntentPersistenceException(
                        "Provisioning intent consumption returned "
                                + "multiple durable rows",
                        null);
            }

            return consumed.stream()
                    .findFirst();

        } catch (DataAccessException exception) {

            throw new StaffProvisioningIntentPersistenceException(
                    "Failed to consume Staff provisioning intent",
                    exception);
        }
    }

    /** Cancels a pending Tenant intent by opaque ID; terminal repeats do not produce another effect. */
    @Override
    public boolean cancelPending(
            UUID tenantId,
            UUID intentId,
            OffsetDateTime cancelledAt) {

        requireIdentifier(
                tenantId,
                "Tenant ID");

        requireIdentifier(
                intentId,
                "Provisioning intent ID");

        if (cancelledAt == null) {
            throw new IllegalArgumentException(
                    "Cancellation time is required");
        }

        try {

            var updated =
                    jdbcTemplate.update(
                            """
                            UPDATE workforce.staff_provisioning_intents
                            SET cancelled_at = ?
                            WHERE tenant_id = ?
                              AND intent_id = ?
                              AND consumed_at IS NULL
                              AND cancelled_at IS NULL
                              AND created_at <= ?
                            """,
                            cancelledAt,
                            tenantId,
                            intentId,
                            cancelledAt);

            return updated == 1;

        } catch (DataAccessException exception) {

            throw new StaffProvisioningIntentPersistenceException(
                    "Failed to cancel Staff provisioning intent",
                    exception);
        }
    }

    /** Reads immutable issuance identity for replay without holding locks in the opposite consumption order. */
    private List<PersistedCreationIdentity> findCreationIdentity(UUID tenantId, UUID operationId) {
        return jdbcTemplate.query("""
                SELECT intent_id, request_fingerprint
                FROM workforce.staff_provisioning_intents
                WHERE tenant_id = ? AND operation_id = ?
                """, (row, index) -> new PersistedCreationIdentity(row.getObject("intent_id", UUID.class),
                        row.getBytes("request_fingerprint")), tenantId, operationId);
    }

    /** Distinguishes matching replay from operation reuse with different frozen facts. */
    private StaffProvisioningIntentCreation recognizeCreationReplay(List<PersistedCreationIdentity> persisted,
            byte[] fingerprint) {
        if (persisted.size() != 1) {
            throw new StaffProvisioningIntentPersistenceException(
                    "Staff provisioning intent operation conflict did not resolve to exactly one durable row", null);
        }
        var existing = persisted.getFirst();
        if (!Arrays.equals(existing.requestFingerprint(), fingerprint)) {
            return new StaffProvisioningIntentCreation.FingerprintConflict();
        }
        return new StaffProvisioningIntentCreation.Replay(existing.intentId());
    }

    private record PersistedCreationIdentity(
            UUID intentId,
            byte[] requestFingerprint) {

        private PersistedCreationIdentity {

            if (intentId == null) {
                throw new IllegalArgumentException(
                        "Persisted provisioning intent ID is required");
            }

            requestFingerprint =
                    requestFingerprint == null
                            ? null
                            : requestFingerprint.clone();
        }

        /** Requires the canonical operation fingerprint before classifying durable replay. */
        @Override
        public byte[] requestFingerprint() {

            return requestFingerprint == null
                    ? null
                    : requestFingerprint.clone();
        }
    }

    /** Rejects missing internal selectors before persistence or canonical fingerprint construction. */
    private static void requireIdentifier(
            UUID value,
            String label) {

        if (value == null) {
            throw new IllegalArgumentException(
                    label + " is required");
        }
    }

    /** Rejects malformed digest material before durable proof lookup or creation. */
    private static void requireDigest(
            byte[] secretDigest) {

        if (secretDigest == null) {
            throw new IllegalArgumentException(
                    "Provisioning secret digest is required");
        }

        if (secretDigest.length != SHA_256_BYTES) {
            throw new IllegalArgumentException(
                    "Provisioning secret digest must contain 32 bytes");
        }
    }
}
