package io.github.piresrenan.orderhub.catalog.application.port.in.identity;
import java.util.UUID;
/** Validates Catalog ownership inside the consuming workflow's transaction. */
public interface ValidateVariantIdentityUseCase {
    /** Stabilizes existing identity without requiring any particular commercial lifecycle. */
    void validate(UUID tenantId,UUID variantId);
}
