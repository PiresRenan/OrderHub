package io.github.piresrenan.orderhub.catalog.application.port.in.administration;
import java.util.Objects;
import java.util.UUID;
/** Internal actor and Tenant identity reconciled at the trusted HTTP boundary. */
public record CatalogAdminContext(UUID userId, UUID tenantId, String correlationId) {
    /** Requires complete internal identity and a bounded safe correlation value. */
    public CatalogAdminContext {
        Objects.requireNonNull(userId, "userId"); Objects.requireNonNull(tenantId, "tenantId");
        if (correlationId == null || !correlationId.matches("[A-Za-z0-9._:-]{1,128}"))
            throw new IllegalArgumentException("Invalid correlation identity");
    }
}
