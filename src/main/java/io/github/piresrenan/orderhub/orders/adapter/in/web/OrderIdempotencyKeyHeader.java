package io.github.piresrenan.orderhub.orders.adapter.in.web;

import java.util.List;

import org.springframework.http.HttpHeaders;

/**
 * Validates the transport-level Idempotency-Key contract for create-Order.
 *
 * <p>
 * This type intentionally remains inside the HTTP adapter. It validates only
 * header representation rules and does not define durable idempotency identity,
 * hashing, fingerprinting or persistence semantics.
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
     * Requires exactly one syntactically valid Idempotency-Key header value.
     *
     * @param headers HTTP request headers
     * @return validated opaque header value
     * @throws OrderIdempotencyKeyInvalidException when the header contract is
     *         violated
     */
    static String requireValid(
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

        return value;
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

            /*
             * ADR-0010 accepts visible ASCII only.
             *
             * 0x21 through 0x7E excludes spaces, tabs and control characters.
             * Comma is additionally rejected so one field value cannot become an
             * ambiguous representation of multiple logical keys.
             */
            if (character < 0x21
                    || character > 0x7E
                    || character == ',') {

                return false;
            }
        }

        return true;
    }
}
