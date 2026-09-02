package io.github.piresrenan.orderhub.orders.application.port.in;

import java.util.Arrays;
import java.util.Objects;

/**
 * Immutable SHA-256 identity of a create-Order idempotency key.
 *
 * <p>
 * Raw client-provided idempotency keys must not cross the application boundary.
 * This value contains only the fixed-width cryptographic digest used to identify
 * the accepted key.
 * </p>
 */
public final class CreateOrderIdempotencyKeyDigest {

    private static final int LENGTH_BYTES =
            32;

    private final byte[] bytes;

    private CreateOrderIdempotencyKeyDigest(
            byte[] bytes) {

        this.bytes =
                bytes;
    }

    /**
     * Creates a digest identity from exactly one SHA-256-sized byte sequence.
     *
     * @param bytes 32-byte digest
     * @return immutable digest identity
     */
    public static CreateOrderIdempotencyKeyDigest of(
            byte[] bytes) {

        Objects.requireNonNull(
                bytes,
                "bytes");

        if (bytes.length != LENGTH_BYTES) {
            throw new IllegalArgumentException(
                    "Create-order idempotency key digest must contain exactly 32 bytes");
        }

        return new CreateOrderIdempotencyKeyDigest(
                bytes.clone());
    }

    /**
     * Returns a defensive copy of the digest bytes.
     *
     * @return independent 32-byte copy
     */
    public byte[] bytes() {

        return bytes.clone();
    }

    @Override
    public boolean equals(
            Object other) {

        if (this == other) {
            return true;
        }

        if (!(other instanceof CreateOrderIdempotencyKeyDigest that)) {
            return false;
        }

        return Arrays.equals(
                bytes,
                that.bytes);
    }

    @Override
    public int hashCode() {

        return Arrays.hashCode(
                bytes);
    }

    @Override
    public String toString() {

        return "CreateOrderIdempotencyKeyDigest[redacted]";
    }
}