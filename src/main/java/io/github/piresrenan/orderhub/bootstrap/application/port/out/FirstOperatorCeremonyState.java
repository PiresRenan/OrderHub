package io.github.piresrenan.orderhub.bootstrap.application.port.out;

import java.util.UUID;

/**
 * Locked singleton state; completion metadata is present only once completed.
 *
 * @param completed          whether the ceremony is closed
 * @param operationId        completing operation, or null while open
 * @param operatorUserId     bootstrapped User, or null while open
 * @param requestFingerprint immutable fingerprint of the completing request, or null while open
 */
public record FirstOperatorCeremonyState(boolean completed, UUID operationId, UUID operatorUserId, String requestFingerprint) {
}
