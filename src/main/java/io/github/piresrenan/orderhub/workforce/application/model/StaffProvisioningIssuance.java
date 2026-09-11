package io.github.piresrenan.orderhub.workforce.application.model;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Application outcome of Staff provisioning issuance.
 *
 * <p>Only a newly established intent may expose its one-time credential.
 * Replay can identify the previously established intent but cannot recover
 * credential material because only its digest is durable.</p>
 */
@org.springframework.modulith.NamedInterface("staff-provisioning")
public sealed interface StaffProvisioningIssuance
        permits StaffProvisioningIssuance.Issued,
        StaffProvisioningIssuance.Replay,
        StaffProvisioningIssuance.FingerprintConflict {

    /**
     * A new durable intent was established and its one-time credential can be
     * returned exactly on this successful response path.
     */
    @org.springframework.modulith.NamedInterface("staff-provisioning")
    record Issued(
            UUID intentId,
            String credential,
            OffsetDateTime expiresAt)
            implements StaffProvisioningIssuance {

        /** Redacts credential or provider identity data from incidental textual logging. */
        @Override public String toString() { return "Issued[credential=redacted]"; }

        public Issued {

            if (intentId == null) {
                throw new IllegalArgumentException(
                        "Provisioning intent ID is required");
            }

            if (credential == null
                    || credential.isBlank()) {

                throw new IllegalArgumentException(
                        "Provisioning credential is required");
            }

            if (expiresAt == null) {
                throw new IllegalArgumentException(
                        "Provisioning intent expiry is required");
            }
        }
    }

    /**
     * The same canonical operation was already established.
     *
     * <p>No one-time credential is available on replay.</p>
     */
    @org.springframework.modulith.NamedInterface("staff-provisioning")
    record Replay(
            UUID intentId)
            implements StaffProvisioningIssuance {

        public Replay {

            if (intentId == null) {
                throw new IllegalArgumentException(
                        "Provisioning intent ID is required");
            }
        }
    }

    /**
     * The durable operation identity is already bound to a different canonical
     * issuance request.
     */
    @org.springframework.modulith.NamedInterface("staff-provisioning")
    record FingerprintConflict()
            implements StaffProvisioningIssuance {
    }
}
