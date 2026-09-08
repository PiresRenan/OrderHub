package io.github.piresrenan.orderhub.catalog.application.port.out;
import java.util.Optional;
import java.util.UUID;
import io.github.piresrenan.orderhub.catalog.application.port.in.administration.CatalogRevision;
import io.github.piresrenan.orderhub.catalog.domain.model.*;
/** Catalog-owned persistence operations; locking reads require the enclosing administration transaction. */
public interface CatalogAdministrationRepository {
    /** Reads a Tenant-owned Product, optionally taking its administrative write lock. */
    Optional<CatalogRevision<Product>> product(UUID tenantId, UUID id, boolean lock);
    /** Creates a new Product without replacing any existing identity. */
    void insertProduct(Product product);
    /** Changes an expected revision within the caller's authoritative transaction. */
    void updateProduct(Product product, long expectedRevision);
    /** Stabilizes a sellable child before the parent Product lock. */
    Optional<ProductVariant> lockActivationWitness(UUID tenantId, UUID productId);
    /** Persists accountability evidence atomically with the associated mutation. */
    void appendAudit(CatalogAuditEvidence evidence);
    /** Finds only a Tenant-owned Variant, optionally stabilizing it for an administrative write. */
    Optional<CatalogRevision<ProductVariant>> variant(UUID tenantId, UUID id, boolean lock);
    /** Creates an identity once; existing identity is never repurposed. */
    void insertVariant(ProductVariant variant);
    /** Changes only the expected revision of the existing Variant. */
    void updateVariant(ProductVariant variant, long expectedRevision);
}
