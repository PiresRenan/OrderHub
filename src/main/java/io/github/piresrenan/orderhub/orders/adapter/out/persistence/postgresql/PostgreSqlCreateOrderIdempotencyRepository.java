package io.github.piresrenan.orderhub.orders.adapter.out.persistence.postgresql;

import java.sql.SQLException;
import java.time.Duration;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import io.github.piresrenan.orderhub.orders.application.idempotency.CreateOrderRequestFingerprint;
import io.github.piresrenan.orderhub.orders.application.port.in.CreateOrderAllocationOutcome;
import io.github.piresrenan.orderhub.orders.application.port.in.CreateOrderIdempotencyKeyDigest;
import io.github.piresrenan.orderhub.orders.application.port.out.CreateOrderIdempotencyAcquisition;
import io.github.piresrenan.orderhub.orders.application.port.out.CreateOrderIdempotencyCompletion;
import io.github.piresrenan.orderhub.orders.application.port.out.CreateOrderIdempotencyInProgressException;
import io.github.piresrenan.orderhub.orders.application.port.out.CreateOrderIdempotencyPersistenceException;
import io.github.piresrenan.orderhub.orders.application.port.out.CreateOrderIdempotencyRepository;
import io.github.piresrenan.orderhub.orders.domain.model.OrderStatus;

/**
 * PostgreSQL authority for durable create-Order idempotency acquisition,
 * completion and replay.
 *
 * <p>
 * Transaction ownership intentionally remains outside this adapter. The
 * caller must execute acquisition, business effects and completion inside one
 * physical transaction.
 * </p>
 */
public final class PostgreSqlCreateOrderIdempotencyRepository
        implements CreateOrderIdempotencyRepository {

    private static final String OPERATION =
            "CREATE_ORDER_V1";

    private static final String LOCK_NOT_AVAILABLE_SQL_STATE =
            "55P03";

    private static final String CURRENT_LOCK_TIMEOUT_SQL =
            """
            SELECT current_setting(
                'lock_timeout'
            )
            """;

    private static final String SET_LOCK_TIMEOUT_SQL =
            """
            SELECT set_config(
                'lock_timeout',
                ?,
                true
            )
            """;

    private static final String ACQUIRE_SQL =
            """
            INSERT INTO orders.order_request_idempotency (
                tenant_id,
                operation,
                key_digest,
                request_fingerprint,
                state,
                created_at
            )
            VALUES (
                ?,
                ?,
                ?,
                ?,
                'PROCESSING',
                CURRENT_TIMESTAMP
            )
            ON CONFLICT (
                tenant_id,
                operation,
                key_digest
            )
            DO NOTHING
            RETURNING 1
            """;

    private static final String FIND_EXISTING_SQL =
            """
            SELECT
                request_fingerprint,
                state,
                order_id,
                order_status,
                allocation_outcome
            FROM orders.order_request_idempotency
            WHERE tenant_id = ?
              AND operation = ?
              AND key_digest = ?
            """;

    private static final String COMPLETE_SQL =
            """
            UPDATE orders.order_request_idempotency
            SET
                state = 'COMPLETED',
                order_id = ?,
                order_status = ?,
                allocation_outcome = ?,
                completed_at = CURRENT_TIMESTAMP
            WHERE tenant_id = ?
              AND operation = ?
              AND key_digest = ?
              AND request_fingerprint = ?
              AND state = 'PROCESSING'
            """;

    private final JdbcTemplate jdbcTemplate;

    private final String acquisitionLockTimeout;

    public PostgreSqlCreateOrderIdempotencyRepository(
            JdbcTemplate jdbcTemplate,
            Duration acquisitionTimeout) {

        this.jdbcTemplate =
                Objects.requireNonNull(
                        jdbcTemplate,
                        "jdbcTemplate");

        this.acquisitionLockTimeout =
                toPostgreSqlTimeout(
                        acquisitionTimeout);
    }

    @Override
    public CreateOrderIdempotencyAcquisition acquire(
            UUID tenantId,
            CreateOrderIdempotencyKeyDigest keyDigest,
            CreateOrderRequestFingerprint fingerprint) {

        Objects.requireNonNull(
                tenantId,
                "tenantId");

        Objects.requireNonNull(
                keyDigest,
                "keyDigest");

        Objects.requireNonNull(
                fingerprint,
                "fingerprint");

        var previousLockTimeout =
                currentLockTimeout();

        setLocalLockTimeout(
                acquisitionLockTimeout);

        final boolean inserted;

        try {

            inserted =
                    !jdbcTemplate.query(
                                    ACQUIRE_SQL,
                                    (resultSet, rowNumber) ->
                                            resultSet.getInt(
                                                    1),
                                    tenantId,
                                    OPERATION,
                                    keyDigest.bytes(),
                                    fingerprint.bytes())
                            .isEmpty();

        } catch (DataAccessException exception) {

            if (hasSqlState(
                    exception,
                    LOCK_NOT_AVAILABLE_SQL_STATE)) {

                throw new CreateOrderIdempotencyInProgressException(
                        exception);
            }

            throw persistenceFailure(
                    exception);
        }

        /*
         * Restore before any later Catalog/Inventory work.
         *
         * If ACQUIRE_SQL raised an ERROR PostgreSQL has already marked the
         * transaction failed; in that path we deliberately do not issue
         * another SQL statement and let the caller roll the transaction back.
         */
        restoreLocalLockTimeout(
                previousLockTimeout);

        if (inserted) {
            return new CreateOrderIdempotencyAcquisition.Acquired();
        }

        return loadCommittedConflict(
                tenantId,
                keyDigest,
                fingerprint);
    }

    @Override
    public void complete(
            UUID tenantId,
            CreateOrderIdempotencyKeyDigest keyDigest,
            CreateOrderRequestFingerprint fingerprint,
            CreateOrderIdempotencyCompletion completion) {

        Objects.requireNonNull(
                tenantId,
                "tenantId");

        Objects.requireNonNull(
                keyDigest,
                "keyDigest");

        Objects.requireNonNull(
                fingerprint,
                "fingerprint");

        Objects.requireNonNull(
                completion,
                "completion");

        try {

            var updated =
                    jdbcTemplate.update(
                            COMPLETE_SQL,
                            completion.orderId(),
                            completion.orderStatus().name(),
                            completion.allocationOutcome().name(),
                            tenantId,
                            OPERATION,
                            keyDigest.bytes(),
                            fingerprint.bytes());

            if (updated != 1) {
                throw new CreateOrderIdempotencyPersistenceException(
                        "Create-order idempotency completion did not update exactly one owned PROCESSING row.");
            }

        } catch (DataAccessException exception) {
            throw persistenceFailure(
                    exception);
        }
    }

    private CreateOrderIdempotencyAcquisition loadCommittedConflict(
            UUID tenantId,
            CreateOrderIdempotencyKeyDigest keyDigest,
            CreateOrderRequestFingerprint fingerprint) {

        try {

            var rows =
                    jdbcTemplate.query(
                            FIND_EXISTING_SQL,
                            (resultSet, rowNumber) ->
                                    new PersistedIdempotencyRecord(
                                            resultSet.getBytes(
                                                    "request_fingerprint"),
                                            resultSet.getString(
                                                    "state"),
                                            resultSet.getObject(
                                                    "order_id",
                                                    UUID.class),
                                            resultSet.getString(
                                                    "order_status"),
                                            resultSet.getString(
                                                    "allocation_outcome")),
                            tenantId,
                            OPERATION,
                            keyDigest.bytes());

            if (rows.size() != 1) {
                throw new CreateOrderIdempotencyPersistenceException(
                        "Create-order idempotency conflict did not resolve to exactly one durable row.");
            }

            var persisted =
                    rows.getFirst();

            if (!Arrays.equals(
                    persisted.requestFingerprint(),
                    fingerprint.bytes())) {

                return new CreateOrderIdempotencyAcquisition.FingerprintConflict();
            }

            if (!"COMPLETED".equals(
                    persisted.state())) {

                throw new CreateOrderIdempotencyPersistenceException(
                        "Committed create-order idempotency row is not COMPLETED.");
            }

            if (persisted.orderId() == null
                    || persisted.orderStatus() == null
                    || persisted.allocationOutcome() == null) {

                throw new CreateOrderIdempotencyPersistenceException(
                        "Completed create-order idempotency row has an incomplete replay projection.");
            }

            var completion =
                    new CreateOrderIdempotencyCompletion(
                            persisted.orderId(),
                            OrderStatus.valueOf(
                                    persisted.orderStatus()),
                            CreateOrderAllocationOutcome.valueOf(
                                    persisted.allocationOutcome()));

            return new CreateOrderIdempotencyAcquisition.Replay(
                    completion);

        } catch (DataAccessException exception) {
            throw persistenceFailure(
                    exception);
        } catch (IllegalArgumentException exception) {
            throw new CreateOrderIdempotencyPersistenceException(
                    "Completed create-order idempotency row contains an unsupported replay projection.",
                    exception);
        }
    }

    private String currentLockTimeout() {

        try {

            var value =
                    jdbcTemplate.queryForObject(
                            CURRENT_LOCK_TIMEOUT_SQL,
                            String.class);

            if (value == null) {
                throw new CreateOrderIdempotencyPersistenceException(
                        "PostgreSQL did not return the current lock_timeout.");
            }

            return value;

        } catch (DataAccessException exception) {
            throw persistenceFailure(
                    exception);
        }
    }

    private void setLocalLockTimeout(
            String value) {

        try {

            jdbcTemplate.queryForObject(
                    SET_LOCK_TIMEOUT_SQL,
                    String.class,
                    value);

        } catch (DataAccessException exception) {
            throw persistenceFailure(
                    exception);
        }
    }

    private void restoreLocalLockTimeout(
            String previousValue) {

        try {

            jdbcTemplate.queryForObject(
                    SET_LOCK_TIMEOUT_SQL,
                    String.class,
                    previousValue);

        } catch (DataAccessException exception) {
            throw persistenceFailure(
                    exception);
        }
    }

    private static String toPostgreSqlTimeout(
            Duration duration) {

        Objects.requireNonNull(
                duration,
                "acquisitionTimeout");

        if (duration.isZero()
                || duration.isNegative()) {

            throw new IllegalArgumentException(
                    "Idempotency acquisition timeout must be greater than zero");
        }

        final long milliseconds;

        try {
            milliseconds =
                    duration.toMillis();
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException(
                    "Idempotency acquisition timeout exceeds supported range",
                    exception);
        }

        if (milliseconds <= 0) {
            throw new IllegalArgumentException(
                    "Idempotency acquisition timeout must be at least one millisecond");
        }

        return milliseconds
                + "ms";
    }

    private static boolean hasSqlState(
            Throwable failure,
            String expectedSqlState) {

        var current =
                failure;

        while (current != null) {

            if (current instanceof SQLException sqlException
                    && expectedSqlState.equals(
                            sqlException.getSQLState())) {

                return true;
            }

            current =
                    current.getCause();
        }

        return false;
    }

    private static CreateOrderIdempotencyPersistenceException persistenceFailure(
            DataAccessException exception) {

        return new CreateOrderIdempotencyPersistenceException(
                "Create-order idempotency persistence operation failed.",
                exception);
    }

    private record PersistedIdempotencyRecord(
            byte[] requestFingerprint,
            String state,
            UUID orderId,
            String orderStatus,
            String allocationOutcome) {

        private PersistedIdempotencyRecord {
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
}
