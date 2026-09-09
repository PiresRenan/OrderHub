package io.github.piresrenan.orderhub.workforce.application.port.out;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import io.github.piresrenan.orderhub.workforce.application.model.ConsumedStaffProvisioningIntent;

/**
 * Workforce persistence authority for one-time Staff provisioning intents.
 */
public interface StaffProvisioningIntentRepository {

    /**
     * Consumes one still-pending, non-expired intent identified by secret digest.
     *
     * <p>Unknown, expired, cancelled or already-consumed intents are deliberately
     * indistinguishable through an empty result.</p>
     */
    Optional<ConsumedStaffProvisioningIntent> consumePending(
            byte[] secretDigest,
            OffsetDateTime consumedAt);

    /**
     * Explicitly cancels one still-pending intent within its Tenant.
     *
     * <p>Unknown, cross-Tenant, consumed or already-cancelled intents return
     * {@code false}. Expiry does not prevent explicit administrative
     * cancellation.</p>
     */
    boolean cancelPending(
            UUID tenantId,
            UUID intentId,
            OffsetDateTime cancelledAt);
}
