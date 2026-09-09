package io.github.piresrenan.orderhub.workforce.application.model;

import java.util.UUID;

/**
 * Durable outcome of attempting to establish one Staff provisioning intent.
 *
 * <p>A replay never contains the one-time secret because only its digest is
 * durable. Recovering a lost raw credential is deliberately impossible.</p>
 */
public sealed interface StaffProvisioningIntentCreation
        permits StaffProvisioningIntentCreation.Created,
        StaffProvisioningIntentCreation.Replay,
        StaffProvisioningIntentCreation.FingerprintConflict {

    /**
     * This attempt established the durable provisioning intent.
     *
     * @param intentId newly established intent identity
     */
    record Created(
            UUID intentId)
            implements StaffProvisioningIntentCreation {

        public Created {

            if (intentId == null) {
                throw new IllegalArgumentException(
                        "Provisioning intent ID is required");
            }
        }
    }

    /**
     * The same operation already established the same canonical request.
     *
     * <p>No raw credential can be reconstructed from this outcome.</p>
     *
     * @param intentId previously established intent identity
     */
    record Replay(
            UUID intentId)
            implements StaffProvisioningIntentCreation {

        public Replay {

            if (intentId == null) {
                throw new IllegalArgumentException(
                        "Provisioning intent ID is required");
            }
        }
    }

    /**
     * The operation identity is already bound to a different canonical request.
     */
    record FingerprintConflict()
            implements StaffProvisioningIntentCreation {
    }
}
