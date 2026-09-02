package io.github.piresrenan.orderhub.orders.support;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;

import io.github.piresrenan.orderhub.orders.application.port.in.CreateOrderIdempotencyKeyDigest;

/**
 * Creates deterministic synthetic SHA-256 idempotency identities for tests.
 *
 * <p>
 * Independent logical create-Order attempts must use independent markers.
 * Tests that deliberately exercise retry semantics may intentionally reuse
 * the same marker.
 * </p>
 */
public final class TestCreateOrderIdempotencyKeyDigests {

    private TestCreateOrderIdempotencyKeyDigests() {
    }

    public static CreateOrderIdempotencyKeyDigest from(
            String marker) {

        Objects.requireNonNull(
                marker,
                "marker");

        try {

            var digest =
                    MessageDigest
                            .getInstance(
                                    "SHA-256")
                            .digest(
                                    marker.getBytes(
                                            StandardCharsets.UTF_8));

            return CreateOrderIdempotencyKeyDigest.of(
                    digest);

        } catch (NoSuchAlgorithmException exception) {

            throw new IllegalStateException(
                    "SHA-256 digest algorithm is unavailable",
                    exception);
        }
    }
}
