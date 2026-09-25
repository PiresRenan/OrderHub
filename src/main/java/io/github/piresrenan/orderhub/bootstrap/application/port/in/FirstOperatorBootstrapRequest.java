package io.github.piresrenan.orderhub.bootstrap.application.port.in;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

/**
 * Exact bootstrap coordinates: the retained issuer, the opaque subject and the
 * OrderHub-owned ceremony operation identifier.
 *
 * <p>Values are never trimmed, case-folded or otherwise normalized. Values
 * with leading or trailing whitespace, control characters, blank content or
 * more than 1024 UTF-8 bytes (the binding column bound) are rejected rather
 * than repaired. {@link #toString()} never renders issuer or subject.</p>
 *
 * @param issuer      exact external issuer, later checked against configured trust
 * @param subject     exact opaque external subject
 * @param operationId OrderHub ceremony operation identifier
 */
public record FirstOperatorBootstrapRequest(String issuer, String subject, UUID operationId) {

    /** Mirrors the binding column bound so invalid input fails before any transaction starts. */
    public static final int MAX_UTF8_BYTES = 1024;

    /**
     * Validates structural usability without transforming any value.
     *
     * @throws IllegalArgumentException with a value-free message when invalid
     */
    public FirstOperatorBootstrapRequest {
        requireExact(issuer, "issuer");
        requireExact(subject, "subject");
        Objects.requireNonNull(operationId, "operationId");
    }

    /** Rejects values that would need normalization to become usable identity coordinates. */
    private static void requireExact(String value, String name) {
        if (value == null || value.isBlank()
                || value.getBytes(StandardCharsets.UTF_8).length > MAX_UTF8_BYTES
                || !value.strip().equals(value)
                || value.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Bootstrap " + name + " is not an exact usable value");
        }
    }

    /** Renders only the non-sensitive operation identifier. */
    @Override
    public String toString() {
        return "FirstOperatorBootstrapRequest[operationId=" + operationId + "]";
    }
}
