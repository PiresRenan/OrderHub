package io.github.piresrenan.orderhub.bootstrap.application.service;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import io.github.piresrenan.orderhub.bootstrap.application.port.in.FirstOperatorBootstrapRequest;

/**
 * Immutable replay identity of one exact ceremony request (ADR-0022).
 *
 * <p>The digest covers a versioned domain, the operation id's 16 bytes and the
 * exact issuer and subject, each written as its UTF-8 byte length followed by
 * its bytes. The unambiguous encoding keeps distinct requests from sharing a
 * preimage. Including the operation id keeps the value specific to one ceremony
 * request rather than making it a reusable pseudonym for the external identity.
 * It is neither a credential nor an authority, and it is never logged.</p>
 */
final class FirstOperatorRequestFingerprint {

    private static final byte[] DOMAIN = "orderhub:first-operator-bootstrap:v1".getBytes(StandardCharsets.US_ASCII);

    private FirstOperatorRequestFingerprint() {
    }

    /**
     * Derives the lowercase hex SHA-256 fingerprint of an exact request.
     *
     * @param request exact issuer, subject and operation id
     * @return 64 lowercase hexadecimal characters
     */
    static String of(FirstOperatorBootstrapRequest request) {
        try {
            var material = new ByteArrayOutputStream();
            var canonical = new DataOutputStream(material);
            canonical.writeInt(DOMAIN.length);
            canonical.write(DOMAIN);
            canonical.writeLong(request.operationId().getMostSignificantBits());
            canonical.writeLong(request.operationId().getLeastSignificantBits());
            writeLengthPrefixed(canonical, request.issuer());
            writeLengthPrefixed(canonical, request.subject());
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(material.toByteArray()));
        } catch (IOException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Bootstrap request fingerprint could not be derived", exception);
        }
    }

    /** Writes exact UTF-8 bytes behind their length so adjacent values cannot be re-split. */
    private static void writeLengthPrefixed(DataOutputStream canonical, String value) throws IOException {
        var bytes = value.getBytes(StandardCharsets.UTF_8);
        canonical.writeInt(bytes.length);
        canonical.write(bytes);
    }
}
