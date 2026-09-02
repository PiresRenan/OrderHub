package io.github.piresrenan.orderhub.orders.adapter.in.web;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

import org.springframework.http.HttpHeaders;

import io.github.piresrenan.orderhub.orders.application.port.in.CreateOrderIdempotencyKeyDigest;

/**
 * Owns the transport-level Idempotency-Key contract for create-Order.
 *
 * <p>
 * The raw header value remains confined to this HTTP adapter. Once syntax is
 * accepted, it is immediately translated into a SHA-256 digest identity before
 * crossing into the application boundary.
 * </p>
 */
final class OrderIdempotencyKeyHeader {

    static final String NAME =
            "Idempotency-Key";

    static final int MAX_LENGTH =
            128;

    private OrderIdempotencyKeyHeader() {
    }

    /**
     * Requires exactly one syntactically valid Idempotency-Key and converts it
     * into the application-safe cryptographic identity.
     *
     * @param headers HTTP request headers
     * @return SHA-256 identity of the accepted opaque key
     * @throws OrderIdempotencyKeyInvalidException when the header contract is
     *         violated
     */
    static CreateOrderIdempotencyKeyDigest requireValid(
            HttpHeaders headers) {

        List<String> values =
                headers.get(NAME);

        if (values == null
                || values.size() != 1) {

            throw new OrderIdempotencyKeyInvalidException();
        }

        var value =
                values.get(0);

        if (!isValid(value)) {
            throw new OrderIdempotencyKeyInvalidException();
        }

        return CreateOrderIdempotencyKeyDigest.of(
                sha256(
                        value));
    }

    private static boolean isValid(
            String value) {

        if (value == null
                || value.isEmpty()
                || value.length() > MAX_LENGTH) {

            return false;
        }

        for (int index = 0;
                index < value.length();
                index++) {

            var character =
                    value.charAt(index);

            if (character < 0x21
                    || character > 0x7E
                    || character == ',') {

                return false;
            }
        }

        return true;
    }

    private static byte[] sha256(
            String value) {

        try {

            return MessageDigest
                    .getInstance(
                            "SHA-256")
                    .digest(
                            value.getBytes(
                                    StandardCharsets.UTF_8));

        } catch (NoSuchAlgorithmException exception) {

            throw new IllegalStateException(
                    "SHA-256 digest algorithm is unavailable",
                    exception);
        }
    }
}