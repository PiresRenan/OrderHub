package io.github.piresrenan.orderhub.orders.support;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Publishes the readiness evidence of a replica process for the multi-replica acceptance test.
 *
 * <p>
 * The acceptance test treats the existence of the ready file as readiness and reads it at once.
 * {@code Files.writeString} creates the file before writing its content, so the evidence is written
 * to a sibling temporary file and moved into place atomically: the ready file becomes visible only
 * with its complete content.
 * </p>
 */
final class ReplicaReadinessEvidence {

    private ReplicaReadinessEvidence() {
    }

    /**
     * Makes the ready path visible only after the evidence is completely written.
     *
     * @param readyPath final readiness path observed by the acceptance test
     * @param evidence complete readiness evidence
     * @throws IOException when the temporary file cannot be written or moved into place
     */
    static void publish(
            Path readyPath,
            String evidence)
            throws IOException {

        var temporaryPath =
                readyPath.resolveSibling(
                        readyPath.getFileName() + ".tmp");

        Files.writeString(
                temporaryPath,
                evidence,
                StandardCharsets.UTF_8);

        Files.move(
                temporaryPath,
                readyPath,
                StandardCopyOption.ATOMIC_MOVE);
    }
}
