package io.github.piresrenan.orderhub.orders.application.idempotency;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

import io.github.piresrenan.orderhub.orders.application.port.in.CreateOrderCommand;

/**
 * Immutable fingerprint of the canonical create-Order business request.
 *
 * <p>
 * The fingerprint identifies the semantic request independently from the
 * Idempotency-Key used to identify a retry attempt.
 * </p>
 *
 * <p>
 * Version 1 preserves Order item sequence and multiplicity and hashes a
 * deterministic binary representation with SHA-256.
 * </p>
 */
public final class CreateOrderRequestFingerprint {

    private static final int VERSION =
            1;

    private static final String OPERATION =
            "CREATE_ORDER_V1";

    private static final int UUID_BYTES =
            Long.BYTES * 2;

    private static final int ITEM_BYTES =
            Integer.BYTES
                    + UUID_BYTES
                    + Integer.BYTES;

    private final byte[] bytes;

    private CreateOrderRequestFingerprint(
            byte[] bytes) {

        this.bytes =
                bytes.clone();
    }

    /**
     * Derives the version-1 request fingerprint from the validated application
     * command.
     *
     * <p>
     * The Idempotency-Key digest carried by the command is deliberately not part
     * of the canonical request representation.
     * </p>
     *
     * @param command create-Order business command
     * @return immutable SHA-256 request fingerprint
     */
    public static CreateOrderRequestFingerprint from(
            CreateOrderCommand command) {

        Objects.requireNonNull(
                command,
                "command");

        Objects.requireNonNull(
                command.tenantId(),
                "command.tenantId");

        Objects.requireNonNull(
                command.customerId(),
                "command.customerId");

        Objects.requireNonNull(
                command.items(),
                "command.items");

        return new CreateOrderRequestFingerprint(
                sha256(
                        canonicalBytes(
                                command)));
    }

    /**
     * Returns an independent copy of the 32-byte SHA-256 fingerprint.
     *
     * @return defensive copy of fingerprint bytes
     */
    public byte[] bytes() {

        return bytes.clone();
    }

    private static byte[] canonicalBytes(
            CreateOrderCommand command) {

        var operationBytes =
                OPERATION.getBytes(
                        StandardCharsets.UTF_8);

        var itemCount =
                command.items()
                        .size();

        var fixedBytes =
                Math.addExact(
                        Integer.BYTES,
                        Math.addExact(
                                Integer.BYTES,
                                Math.addExact(
                                        operationBytes.length,
                                        Math.addExact(
                                                UUID_BYTES,
                                                Math.addExact(
                                                        UUID_BYTES,
                                                        Integer.BYTES)))));

        var itemsBytes =
                Math.multiplyExact(
                        itemCount,
                        ITEM_BYTES);

        var totalBytes =
                Math.addExact(
                        fixedBytes,
                        itemsBytes);

        var canonical =
                ByteBuffer
                        .allocate(
                                totalBytes)
                        .order(
                                ByteOrder.BIG_ENDIAN);

        canonical.putInt(
                VERSION);

        canonical.putInt(
                operationBytes.length);

        canonical.put(
                operationBytes);

        putUuid(
                canonical,
                command.tenantId());

        putUuid(
                canonical,
                command.customerId());

        canonical.putInt(
                itemCount);

        for (int itemIndex = 0;
                itemIndex < itemCount;
                itemIndex++) {

            var item =
                    Objects.requireNonNull(
                            command.items()
                                    .get(
                                            itemIndex),
                            "command.items[" + itemIndex + "]");

            canonical.putInt(
                    itemIndex);

            putUuid(
                    canonical,
                    Objects.requireNonNull(
                            item.variantId(),
                            "command.items[" + itemIndex + "].variantId"));

            canonical.putInt(
                    item.quantity());
        }

        return canonical.array();
    }

    private static void putUuid(
            ByteBuffer target,
            UUID value) {

        target.putLong(
                value.getMostSignificantBits());

        target.putLong(
                value.getLeastSignificantBits());
    }

    private static byte[] sha256(
            byte[] canonical) {

        try {

            return MessageDigest
                    .getInstance(
                            "SHA-256")
                    .digest(
                            canonical);

        } catch (NoSuchAlgorithmException exception) {

            throw new IllegalStateException(
                    "SHA-256 digest algorithm is unavailable",
                    exception);
        }
    }

    @Override
    public boolean equals(
            Object other) {

        if (this == other) {
            return true;
        }

        if (!(other instanceof CreateOrderRequestFingerprint that)) {
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

        return "CreateOrderRequestFingerprint[redacted]";
    }
}
