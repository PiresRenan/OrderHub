package io.github.piresrenan.orderhub.orders.application.port.in;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class CreateOrderIdempotencyKeyDigestTest {

    @Test
    void defensivelyCopiesInputAndOutputBytes() {

        var source =
                new byte[32];

        source[0] =
                1;

        var digest =
                CreateOrderIdempotencyKeyDigest.of(
                        source);

        source[0] =
                9;

        assertThat(digest.bytes()[0])
                .isEqualTo(
                        (byte) 1);

        var extracted =
                digest.bytes();

        extracted[1] =
                7;

        assertThat(digest.bytes()[1])
                .isEqualTo(
                        (byte) 0);
    }

    @Test
    void comparesDigestIdentitiesByByteContent() {

        var firstBytes =
                new byte[32];

        firstBytes[31] =
                42;

        var secondBytes =
                firstBytes.clone();

        var first =
                CreateOrderIdempotencyKeyDigest.of(
                        firstBytes);

        var second =
                CreateOrderIdempotencyKeyDigest.of(
                        secondBytes);

        assertThat(first)
                .isEqualTo(
                        second);

        assertThat(first.hashCode())
                .isEqualTo(
                        second.hashCode());
    }

    @Test
    void rejectsValuesThatAreNotExactlySha256Width() {

        assertThatThrownBy(() ->
                CreateOrderIdempotencyKeyDigest.of(
                        new byte[31]))
                .isInstanceOf(
                        IllegalArgumentException.class)
                .hasMessage(
                        "Create-order idempotency key digest must contain exactly 32 bytes");

        assertThatThrownBy(() ->
                CreateOrderIdempotencyKeyDigest.of(
                        new byte[33]))
                .isInstanceOf(
                        IllegalArgumentException.class);

        assertThatThrownBy(() ->
                CreateOrderIdempotencyKeyDigest.of(
                        null))
                .isInstanceOf(
                        NullPointerException.class);
    }

    @Test
    void redactsDigestFromDiagnosticRepresentation() {

        var bytes =
                new byte[32];

        bytes[0] =
                99;

        var digest =
                CreateOrderIdempotencyKeyDigest.of(
                        bytes);

        assertThat(digest.toString())
                .isEqualTo(
                        "CreateOrderIdempotencyKeyDigest[redacted]");
    }
}