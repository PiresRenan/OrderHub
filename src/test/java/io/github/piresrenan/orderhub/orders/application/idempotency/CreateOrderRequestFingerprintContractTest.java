package io.github.piresrenan.orderhub.orders.application.idempotency;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.github.piresrenan.orderhub.orders.application.port.in.CreateOrderCommand;
import io.github.piresrenan.orderhub.orders.application.port.in.CreateOrderIdempotencyKeyDigest;

class CreateOrderRequestFingerprintContractTest {

    private static final String FINGERPRINT_TYPE =
            "io.github.piresrenan.orderhub.orders.application.idempotency.CreateOrderRequestFingerprint";

    private static final int FINGERPRINT_VERSION =
            1;

    private static final String OPERATION =
            "CREATE_ORDER_V1";

    private static final UUID TENANT_ID =
            UUID.fromString(
                    "11111111-1111-1111-1111-111111111111");

    private static final UUID CUSTOMER_ID =
            UUID.fromString(
                    "22222222-2222-2222-2222-222222222222");

    private static final UUID VARIANT_A =
            UUID.fromString(
                    "33333333-3333-3333-3333-333333333333");

    private static final UUID VARIANT_B =
            UUID.fromString(
                    "44444444-4444-4444-4444-444444444444");

    @Test
    void fingerprintsCanonicalCreateOrderBusinessCommand()
            throws Exception {

        var command =
                command(
                        keyDigest(
                                (byte) 1),
                        List.of(
                                item(
                                        VARIANT_A,
                                        2),
                                item(
                                        VARIANT_B,
                                        5)));

        Class<?> fingerprintType =
                loadFingerprintType();

        assertThat(fingerprintType)
                .as(
                        "CreateOrderRequestFingerprint must exist before canonical request identity can be persisted")
                .isNotNull();

        var from =
                fingerprintType.getDeclaredMethod(
                        "from",
                        CreateOrderCommand.class);

        assertThat(
                Modifier.isStatic(
                        from.getModifiers()))
                .as(
                        "Fingerprint derivation must be deterministic from CreateOrderCommand")
                .isTrue();

        assertThat(from.getReturnType())
                .isEqualTo(
                        fingerprintType);

        var bytesMethod =
                fingerprintType.getDeclaredMethod(
                        "bytes");

        assertThat(bytesMethod.getReturnType())
                .isEqualTo(
                        byte[].class);

        var actual =
                fingerprintBytes(
                        fingerprintType,
                        from,
                        bytesMethod,
                        command);

        var expected =
                expectedFingerprint(
                        command);

        assertThat(actual)
                .as(
                        "Fingerprint must be SHA-256 of the versioned canonical binary command representation")
                .hasSize(
                        32)
                .containsExactly(
                        expected);

        /*
         * Idempotency key identity is intentionally excluded from the request
         * fingerprint. The same business command under another key remains the
         * same business-request fingerprint.
         */
        var sameBusinessRequestDifferentKey =
                command(
                        keyDigest(
                                (byte) 2),
                        List.of(
                                item(
                                        VARIANT_A,
                                        2),
                                item(
                                        VARIANT_B,
                                        5)));

        var differentKeyFingerprint =
                fingerprintBytes(
                        fingerprintType,
                        from,
                        bytesMethod,
                        sameBusinessRequestDifferentKey);

        assertThat(
                Arrays.equals(
                        actual,
                        differentKeyFingerprint))
                .as(
                        "Changing only Idempotency-Key identity must not change request fingerprint")
                .isTrue();

        /*
         * ADR-0010 preserves list order. Reordering items is therefore not
         * silently considered the same request.
         */
        var reordered =
                command(
                        keyDigest(
                                (byte) 1),
                        List.of(
                                item(
                                        VARIANT_B,
                                        5),
                                item(
                                        VARIANT_A,
                                        2)));

        var reorderedFingerprint =
                fingerprintBytes(
                        fingerprintType,
                        from,
                        bytesMethod,
                        reordered);

        assertThat(
                Arrays.equals(
                        actual,
                        reorderedFingerprint))
                .as(
                        "Item order must participate in request identity")
                .isFalse();

        /*
         * ADR-0010 also preserves multiplicity. Two repeated list entries are
         * not canonicalized into one aggregated quantity.
         */
        var duplicated =
                command(
                        keyDigest(
                                (byte) 1),
                        List.of(
                                item(
                                        VARIANT_A,
                                        1),
                                item(
                                        VARIANT_A,
                                        1)));

        var aggregated =
                command(
                        keyDigest(
                                (byte) 1),
                        List.of(
                                item(
                                        VARIANT_A,
                                        2)));

        var duplicatedFingerprint =
                fingerprintBytes(
                        fingerprintType,
                        from,
                        bytesMethod,
                        duplicated);

        var aggregatedFingerprint =
                fingerprintBytes(
                        fingerprintType,
                        from,
                        bytesMethod,
                        aggregated);

        assertThat(
                Arrays.equals(
                        duplicatedFingerprint,
                        aggregatedFingerprint))
                .as(
                        "Item multiplicity must not be normalized by the idempotency layer")
                .isFalse();
    }

    private static Class<?> loadFingerprintType() {

        try {

            return Class.forName(
                    FINGERPRINT_TYPE);

        } catch (ClassNotFoundException ignored) {

            return null;
        }
    }

    private static byte[] fingerprintBytes(
            Class<?> fingerprintType,
            java.lang.reflect.Method from,
            java.lang.reflect.Method bytesMethod,
            CreateOrderCommand command)
            throws Exception {

        var fingerprint =
                from.invoke(
                        null,
                        command);

        assertThat(fingerprint)
                .isInstanceOf(
                        fingerprintType);

        return (byte[]) bytesMethod.invoke(
                fingerprint);
    }

    /**
     * Independent test oracle for ADR-0010 fingerprint version 1.
     *
     * <p>
     * Every variable-width field carries an explicit byte length. Numeric and
     * UUID values are encoded as fixed-width big-endian primitives.
     * </p>
     */
    private static byte[] expectedFingerprint(
            CreateOrderCommand command)
            throws Exception {

        var canonical =
                new ByteArrayOutputStream();

        try (var output =
                new DataOutputStream(
                        canonical)) {

            output.writeInt(
                    FINGERPRINT_VERSION);

            var operation =
                    OPERATION.getBytes(
                            StandardCharsets.UTF_8);

            output.writeInt(
                    operation.length);

            output.write(
                    operation);

            writeUuid(
                    output,
                    command.tenantId());

            writeUuid(
                    output,
                    command.customerId());

            output.writeInt(
                    command.items()
                            .size());

            for (int itemIndex = 0;
                    itemIndex < command.items().size();
                    itemIndex++) {

                var item =
                        command.items()
                                .get(
                                        itemIndex);

                output.writeInt(
                        itemIndex);

                writeUuid(
                        output,
                        item.variantId());

                output.writeInt(
                        item.quantity());
            }
        }

        return MessageDigest
                .getInstance(
                        "SHA-256")
                .digest(
                        canonical.toByteArray());
    }

    private static void writeUuid(
            DataOutputStream output,
            UUID value)
            throws Exception {

        output.writeLong(
                value.getMostSignificantBits());

        output.writeLong(
                value.getLeastSignificantBits());
    }

    private static CreateOrderCommand command(
            CreateOrderIdempotencyKeyDigest keyDigest,
            List<CreateOrderCommand.Item> items) {

        return new CreateOrderCommand(
                TENANT_ID,
                CUSTOMER_ID,
                items,
                keyDigest);
    }

    private static CreateOrderCommand.Item item(
            UUID variantId,
            int quantity) {

        return new CreateOrderCommand.Item(
                variantId,
                quantity);
    }

    private static CreateOrderIdempotencyKeyDigest keyDigest(
            byte marker) {

        var bytes =
                new byte[32];

        bytes[0] =
                marker;

        return CreateOrderIdempotencyKeyDigest.of(
                bytes);
    }
}