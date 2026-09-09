package io.github.piresrenan.orderhub.users.adapter.out.persistence.postgresql;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;
import java.util.function.Supplier;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import io.github.piresrenan.orderhub.users.application.port.out.ExternalIdentityUserProvisioningCoordinator;

/**
 * PostgreSQL implementation of the Users-owned external identity provisioning
 * coordination scope.
 *
 * <p>Serialization is delegated to a transaction-level PostgreSQL advisory
 * lock, so competing OrderHub instances contend inside the database instead of
 * inside one JVM. The lock is acquired inside the same physical transaction
 * that runs the coordinated work, so PostgreSQL releases it on commit and on
 * rollback and no failed scope can leave an orphaned lock behind.</p>
 *
 * <p>The scope propagates as REQUIRED. An enclosing transaction is joined
 * rather than suspended, so work performed inside the scope commits or rolls
 * back together with the orchestration that owns it.</p>
 *
 * <p>The advisory key is derived from a domain-separated, length-prefixed
 * SHA-256 digest of the exact issuer/subject pair, so every node derives the
 * same key for the same external identity. The key is never persisted, never
 * logged and never crosses the application port. A digest collision can only
 * over-serialize two otherwise independent external identities; it cannot
 * weaken correctness.</p>
 */
public final class PostgreSqlExternalIdentitySerializationCoordinator
        implements ExternalIdentityUserProvisioningCoordinator {

    private static final String ACQUIRE_TRANSACTION_LOCK = """
            SELECT pg_advisory_xact_lock(?)
            """;

    private static final String LOCK_KEY_DOMAIN =
            "orderhub.users.external-identity.provisioning.v1";

    private static final String LOCK_KEY_DIGEST_ALGORITHM =
            "SHA-256";

    private final JdbcTemplate jdbcTemplate;

    private final TransactionTemplate transactionTemplate;

    /**
     * Creates the coordination scope from the PostgreSQL access and transaction
     * demarcation supplied by the composition root.
     *
     * @param jdbcTemplate       PostgreSQL access used to acquire the advisory
     *                           lock inside the coordination scope
     * @param transactionManager transaction demarcation for the coordination
     *                           scope
     */
    public PostgreSqlExternalIdentitySerializationCoordinator(
            JdbcTemplate jdbcTemplate,
            PlatformTransactionManager transactionManager) {

        this.jdbcTemplate =
                Objects.requireNonNull(
                        jdbcTemplate,
                        "jdbcTemplate");

        this.transactionTemplate =
                new TransactionTemplate(
                        Objects.requireNonNull(
                                transactionManager,
                                "transactionManager"));

        transactionTemplate.setPropagationBehavior(
                TransactionDefinition.PROPAGATION_REQUIRED);
    }

    /**
     * Runs the supplied work after every competing scope for the same exact
     * issuer/subject pair has released the identity.
     *
     * <p>Lock acquisition and the work itself share one transaction, so the
     * caller observes durable state that no same-identity scope can be
     * concurrently changing. Issuer and subject are used exactly as supplied;
     * this boundary performs no provider-specific normalization.</p>
     *
     * @param issuer  exact external identity issuer
     * @param subject exact external identity subject
     * @param work    application work executed after same-pair serialization
     * @param <T>     result type
     * @return the exact result produced by the coordinated work
     */
    @Override
    public <T> T executeSerialized(
            String issuer,
            String subject,
            Supplier<T> work) {

        Objects.requireNonNull(
                issuer,
                "issuer");

        Objects.requireNonNull(
                subject,
                "subject");

        Objects.requireNonNull(
                work,
                "work");

        var lockKey =
                lockKey(
                        issuer,
                        subject);

        return transactionTemplate.execute(
                status -> {

                    acquireTransactionLock(
                            lockKey);

                    return work.get();
                });
    }

    /**
     * Blocks until PostgreSQL grants the transaction-scoped advisory lock. The
     * result column is the void return of the lock function and carries no
     * information.
     *
     * @param lockKey advisory lock key for one exact external identity
     */
    private void acquireTransactionLock(
            long lockKey) {

        jdbcTemplate.query(
                ACQUIRE_TRANSACTION_LOCK,
                (resultSet, rowNumber) -> null,
                lockKey);
    }

    /**
     * Derives the advisory lock key for one exact external identity.
     *
     * <p>The digested material is the constant lock domain followed by each
     * value written as its UTF-8 byte length and then its UTF-8 bytes. The
     * explicit lengths keep two different pairs from producing the same
     * material, and the domain keeps this key space separate from every other
     * advisory lock in OrderHub.</p>
     *
     * @param issuer  exact external identity issuer
     * @param subject exact external identity subject
     * @return deterministic key built from the first eight digest bytes
     */
    private static long lockKey(
            String issuer,
            String subject) {

        try {

            var material =
                    new ByteArrayOutputStream();

            var canonical =
                    new DataOutputStream(
                            material);

            canonical.write(
                    LOCK_KEY_DOMAIN.getBytes(
                            StandardCharsets.UTF_8));

            writeLengthPrefixed(
                    canonical,
                    issuer);

            writeLengthPrefixed(
                    canonical,
                    subject);

            var digest =
                    MessageDigest.getInstance(
                                    LOCK_KEY_DIGEST_ALGORITHM)
                            .digest(
                                    material.toByteArray());

            var lockKey = 0L;

            for (var index = 0; index < Long.BYTES; index++) {
                lockKey = (lockKey << 8) | (digest[index] & 0xFFL);
            }

            return lockKey;

        } catch (IOException | NoSuchAlgorithmException exception) {

            throw new IllegalStateException(
                    "External identity serialization key could not be derived",
                    exception);
        }
    }

    private static void writeLengthPrefixed(
            DataOutputStream canonical,
            String value)
            throws IOException {

        var bytes =
                value.getBytes(
                        StandardCharsets.UTF_8);

        canonical.writeInt(
                bytes.length);

        canonical.write(
                bytes);
    }
}
