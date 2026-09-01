package io.github.piresrenan.orderhub.catalog.application.port.in.orderability;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Tenant-scoped Variant identities that must remain commercially orderable
 * while an Order transaction is accepted.
 *
 * @param tenantId trusted Tenant identity
 * @param variantIds requested ProductVariant identities
 */
public record ValidateOrderableVariantsCommand(
        UUID tenantId,
        List<UUID> variantIds) {

    public ValidateOrderableVariantsCommand {

        Objects.requireNonNull(
                tenantId,
                "tenantId");

        Objects.requireNonNull(
                variantIds,
                "variantIds");

        if (variantIds.isEmpty()) {
            throw new IllegalArgumentException(
                    "At least one Variant identity is required");
        }

        if (
            variantIds.stream()
                    .anyMatch(
                            Objects::isNull)
        ) {

            throw new IllegalArgumentException(
                    "Variant identities must not contain null");
        }

        variantIds =
                List.copyOf(
                        variantIds);
    }
}
