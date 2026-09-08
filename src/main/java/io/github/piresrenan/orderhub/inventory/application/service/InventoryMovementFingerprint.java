package io.github.piresrenan.orderhub.inventory.application.service;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import io.github.piresrenan.orderhub.inventory.domain.model.InventoryMovement;

/** Version-one business intent; request correlation and server time are not retry identity. */
public final class InventoryMovementFingerprint {
    /** Prevents instantiation of the canonical encoding utility. */
    private InventoryMovementFingerprint() { }

    /** Hashes explicit versioned business fields, excluding retry-varying server metadata. */
    public static String from(InventoryMovement movement) {
        try {
            var bytes = new ByteArrayOutputStream();
            var output = new DataOutputStream(bytes);
            output.writeInt(1);
            for (var id : new java.util.UUID[]{movement.tenantId(), movement.actorUserId(), movement.variantId()}) {
                output.writeLong(id.getMostSignificantBits());
                output.writeLong(id.getLeastSignificantBits());
            }
            output.writeUTF(movement.type().name());
            output.writeLong(movement.delta());
            var reason = movement.reason().getBytes(StandardCharsets.UTF_8);
            output.writeInt(reason.length);
            output.write(reason);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        } catch (IOException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Inventory fingerprint could not be computed", exception);
        }
    }
}
