package io.github.piresrenan.orderhub.orders.application.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.github.piresrenan.orderhub.orders.application.port.in.CreateOrderCommand;
import io.github.piresrenan.orderhub.orders.application.port.in.CreateOrderIdempotencyKeyDigest;

class CreateOrderRequestFingerprintValueObjectTest {

    private static final UUID TENANT_ID =
            UUID.fromString(
                    "11111111-1111-1111-1111-111111111111");

    private static final UUID CUSTOMER_ID =
            UUID.fromString(
                    "22222222-2222-2222-2222-222222222222");

    private static final UUID VARIANT_ID =
            UUID.fromString(
                    "33333333-3333-3333-3333-333333333333");

    @Test
    void returnsDefensiveCopyOfFingerprintBytes() {

        var fingerprint =
                CreateOrderRequestFingerprint.from(
                        command(
                                (byte) 1));

        var original =
                fingerprint.bytes();

        assertThat(original)
                .hasSize(
                        32);

        var originalFirstByte =
                original[0];

        original[0] =
                (byte) (originalFirstByte ^ 0x7F);

        var reread =
                fingerprint.bytes();

        assertThat(reread)
                .hasSize(
                        32);

        assertThat(reread[0])
                .isEqualTo(
                        originalFirstByte);

        assertThat(reread)
                .isNotSameAs(
                        original);
    }

    @Test
    void comparesFingerprintsByCanonicalDigestContent() {

        var first =
                CreateOrderRequestFingerprint.from(
                        command(
                                (byte) 1));

        var second =
                CreateOrderRequestFingerprint.from(
                        command(
                                (byte) 2));

        assertThat(first)
                .as(
                        "Equivalent business requests must compare equal even when the Idempotency-Key identity differs")
                .isEqualTo(
                        second);

        assertThat(first.hashCode())
                .isEqualTo(
                        second.hashCode());
    }

    @Test
    void redactsFingerprintFromDiagnosticRepresentation() {

        var fingerprint =
                CreateOrderRequestFingerprint.from(
                        command(
                                (byte) 1));

        assertThat(fingerprint.toString())
                .isEqualTo(
                        "CreateOrderRequestFingerprint[redacted]");
    }

    @Test
    void rejectsMissingBusinessCommand() {

        assertThatThrownBy(() ->
                CreateOrderRequestFingerprint.from(
                        null))
                .isInstanceOf(
                        NullPointerException.class)
                .hasMessage(
                        "command");
    }

    private static CreateOrderCommand command(
            byte keyMarker) {

        var keyBytes =
                new byte[32];

        keyBytes[0] =
                keyMarker;

        return new CreateOrderCommand(
                TENANT_ID,
                CUSTOMER_ID,
                List.of(
                        new CreateOrderCommand.Item(
                                VARIANT_ID,
                                3)),
                CreateOrderIdempotencyKeyDigest.of(
                        keyBytes));
    }
}
