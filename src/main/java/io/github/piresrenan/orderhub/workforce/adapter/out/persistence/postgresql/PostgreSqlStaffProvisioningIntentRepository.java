package io.github.piresrenan.orderhub.workforce.adapter.out.persistence.postgresql;

import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Optional;
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

    public PostgreSqlStaffProvisioningIntentRepository(
            JdbcTemplate jdbcTemplate) {

        if (jdbcTemplate == null) {
            throw new IllegalArgumentException(
                    "JdbcTemplate is required");
        }

        this.jdbcTemplate =
                jdbcTemplate;
    }

    @Override
    public StaffProvisioningIntentCreation create(
            NewStaffProvisioningIntent intent) {

        if (intent == null) {
            throw new IllegalArgumentException(
                    "Staff provisioning intent is required");
        }

        try {

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

            var persisted =
                    jdbcTemplate.query(
                            """
                            SELECT
                                intent_id,
                                request_fingerprint
                            FROM workforce.staff_provisioning_intents
                            WHERE tenant_id = ?
                              AND operation_id = ?
                            """,
                            (resultSet, rowNumber) ->
                                    new PersistedCreationIdentity(
                                            resultSet.getObject(
                                                    "intent_id",
                                                    UUID.class),
                                            resultSet.getBytes(
                                                    "request_fingerprint")),
                            intent.tenantId(),
                            intent.operationId());

            if (persisted.size() != 1) {
                throw new StaffProvisioningIntentPersistenceException(
                        "Staff provisioning intent operation conflict "
                                + "did not resolve to exactly one durable row",
                        null);
            }

            var existing =
                    persisted.getFirst();

            if (!Arrays.equals(
                    existing.requestFingerprint(),
                    intent.requestFingerprint())) {

                return new StaffProvisioningIntentCreation.FingerprintConflict();
            }

            return new StaffProvisioningIntentCreation.Replay(
                    existing.intentId());

        } catch (DataAccessException exception) {

            throw new StaffProvisioningIntentPersistenceException(
                    "Failed to create Staff provisioning intent",
                    exception);
        }
    }
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

        @Override
        public byte[] requestFingerprint() {

            return requestFingerprint == null
                    ? null
                    : requestFingerprint.clone();
        }
    }

    private static void requireIdentifier(
            UUID value,
            String label) {

        if (value == null) {
            throw new IllegalArgumentException(
                    label + " is required");
        }
    }

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
